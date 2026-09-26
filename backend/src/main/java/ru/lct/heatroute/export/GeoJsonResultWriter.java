package ru.lct.heatroute.export;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.result.CalculationVariant;
import ru.lct.heatroute.export.ResultFeatureFactory.ResultFeature;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Выгрузка результата в один совмещённый файл GeoJSON (раздел 7 ТП).
 * <p>
 * Запись потоковая: объекты уходят в выходной поток по одному, документ целиком
 * в памяти не собирается. Требование ТЗ — выгрузка до 500 МБ.
 * <p>
 * Состав атрибутов сюда не заложен: он определяется в {@link ResultFeatureFactory},
 * едином для файла и для базы. Так выгрузка и то, что показывает интерфейс,
 * не могут разойтись.
 */
@Slf4j
@Component
public class GeoJsonResultWriter {

    private final JsonFactory factory = new JsonFactory();
    private final ResultFeatureFactory features;

    public GeoJsonResultWriter(ResultFeatureFactory features) {
        this.features = features;
    }

    /** Пишет все варианты одной коллекцией. Возвращает число записанных объектов. */
    public long write(OutputStream out, Collection<CalculationVariant> variants)
            throws IOException {
        long[] count = {0};
        writeCollection(out, sink -> {
            for (CalculationVariant v : variants) {
                features.forEach(v, f -> {
                    sink.accept(f);
                    count[0]++;
                });
            }
        });
        log.debug("Выгружено объектов: {} по {} вариантам", count[0], variants.size());
        return count[0];
    }

    /**
     * Пишет коллекцию из произвольного источника объектов. Используется для выдачи
     * результата прямо из базы: строки читаются курсором и уходят в поток ответа
     * по одной, не собираясь в список.
     */
    public void writeCollection(OutputStream out, FeatureSource source) throws IOException {
        JsonGenerator g = factory.createGenerator(out);
        try {
            g.writeStartObject();
            g.writeStringField("type", "FeatureCollection");

            // Геометрия выходного файла — в WGS 84, как требует раздел 7 ТП.
            g.writeFieldName("crs");
            g.writeStartObject();
            g.writeStringField("type", "name");
            g.writeFieldName("properties");
            g.writeStartObject();
            g.writeStringField("name", "urn:ogc:def:crs:OGC:1.3:CRS84");
            g.writeEndObject();
            g.writeEndObject();

            g.writeArrayFieldStart("features");
            source.forEach(feature -> {
                try {
                    writeFeature(g, feature);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            g.writeEndArray();
            g.writeEndObject();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } finally {
            g.flush();
        }
    }

    /** Источник объектов для потоковой записи. */
    @FunctionalInterface
    public interface FeatureSource {
        void forEach(java.util.function.Consumer<ResultFeature> sink);
    }

    /** Запись одного объекта: геометрия плюс готовый набор атрибутов. */
    public void writeFeature(JsonGenerator g, ResultFeature feature) throws IOException {
        g.writeStartObject();
        g.writeStringField("type", "Feature");
        g.writeFieldName("geometry");
        writeGeometry(g, feature.getGeometry());
        g.writeObjectFieldStart("properties");
        for (Map.Entry<String, Object> e : feature.getProperties().entrySet()) {
            writeValue(g, e.getKey(), e.getValue());
        }
        g.writeEndObject();
        g.writeEndObject();
    }

    // =================================================================================

    private void writeValue(JsonGenerator g, String field, Object value) throws IOException {
        if (value == null) {
            g.writeNullField(field);
        } else if (value instanceof Integer) {
            g.writeNumberField(field, (Integer) value);
        } else if (value instanceof Long) {
            g.writeNumberField(field, (Long) value);
        } else if (value instanceof Double) {
            g.writeNumberField(field, (Double) value);
        } else if (value instanceof Number) {
            g.writeNumberField(field, ((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            g.writeBooleanField(field, (Boolean) value);
        } else if (value instanceof List) {
            g.writeArrayFieldStart(field);
            for (Object item : (List<?>) value) {
                writeItem(g, item);
            }
            g.writeEndArray();
        } else {
            g.writeStringField(field, String.valueOf(value));
        }
    }

    /**
     * Элемент массива с сохранением типа.
     * <p>
     * Раздел 7.2 ТП про {@code unconnected_oks_ids}: «Тип каждого идентификатора
     * сохраняется таким же, как во входных данных». Числовой идентификатор должен
     * остаться числом, иначе ссылка на объект входного набора перестаёт совпадать
     * с ним буквально.
     */
    private void writeItem(JsonGenerator g, Object item) throws IOException {
        if (item == null) {
            g.writeNull();
        } else if (item instanceof Integer) {
            g.writeNumber((Integer) item);
        } else if (item instanceof Long) {
            g.writeNumber((Long) item);
        } else if (item instanceof Double) {
            g.writeNumber((Double) item);
        } else if (item instanceof Number) {
            g.writeNumber(((Number) item).doubleValue());
        } else if (item instanceof Boolean) {
            g.writeBoolean((Boolean) item);
        } else {
            g.writeString(String.valueOf(item));
        }
    }

    /**
     * Геометрия уже приведена к WGS 84 фабрикой объектов, здесь она только
     * записывается в формате GeoJSON.
     */
    private void writeGeometry(JsonGenerator g, Geometry geometry) throws IOException {
        if (geometry == null || geometry.isEmpty()) {
            g.writeNull();
            return;
        }
        g.writeStartObject();
        if (geometry instanceof Point) {
            g.writeStringField("type", "Point");
            g.writeFieldName("coordinates");
            writeCoordinate(g, geometry.getCoordinate());
        } else if (geometry instanceof LineString) {
            g.writeStringField("type", "LineString");
            g.writeArrayFieldStart("coordinates");
            CoordinateSequence seq = ((LineString) geometry).getCoordinateSequence();
            for (int i = 0; i < seq.size(); i++) {
                writeCoordinate(g, seq.getCoordinate(i));
            }
            g.writeEndArray();
        } else {
            throw new IllegalArgumentException(
                    "Выходная геометрия должна быть точкой или линией, получено: "
                            + geometry.getGeometryType());
        }
        g.writeEndObject();
    }

    /**
     * Координаты пишутся плоскими, без третьего числа.
     * <p>
     * Раздел 5 ТП в редакции от 18.09: «Z-координаты в GeoJSON не требуются. Вертикальное
     * положение задаётся атрибутами глубины в начале и конце участка». Внутри расчёта
     * отметка на вершинах остаётся — по ней строится продольный профиль, — но в выгрузку
     * она не идёт: сверять будут плановую геометрию, и лишнее число в координате
     * только мешает.
     */
    private void writeCoordinate(JsonGenerator g, Coordinate c) throws IOException {
        g.writeStartArray();
        g.writeNumber(c.x);
        g.writeNumber(c.y);
        g.writeEndArray();
    }
}
