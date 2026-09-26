package ru.lct.heatroute.ingest;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.geo.Geo;
import ru.lct.heatroute.geo.ProjectionService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Потоковый разбор входного GeoJSON.
 * <p>
 * Требование ТЗ (раздел 3.2) — принимать файл до 3 ГБ, не загружая его целиком
 * в оперативную память. Поэтому документ не десериализуется в дерево: парсер идёт
 * по токенам, собирает ровно один {@code Feature} за раз и сразу отдаёт его
 * потребителю. Координаты переводятся в рабочую проекцию на месте, во время чтения —
 * так в памяти никогда не оказывается второй копии геометрии в градусах.
 * <p>
 * Порядок ключей в объекте не фиксирован: {@code geometry} может идти как до,
 * так и после {@code properties}, и оба варианта встречаются в выгрузках ГИС.
 */
@Slf4j
@Component
public class GeoJsonStreamParser {

    private final JsonFactory jsonFactory = new JsonFactory();
    private final ProjectionService projection;

    public GeoJsonStreamParser(ProjectionService projection) {
        this.projection = projection;
    }

    /**
     * Читает файл и вызывает {@code consumer} на каждом объекте {@code Feature}.
     *
     * @return число прочитанных объектов
     */
    public long parse(Path file, Consumer<RawFeature> consumer) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in, consumer);
        }
    }

    public long parse(InputStream in, Consumer<RawFeature> consumer) throws IOException {
        long count = 0;
        try (JsonParser p = jsonFactory.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new GeoJsonFormatException("Ожидался объект GeoJSON верхнего уровня");
            }
            boolean featuresSeen = false;
            while (p.nextToken() != JsonToken.END_OBJECT && p.currentToken() != null) {
                String field = p.currentName();
                p.nextToken();
                if ("features".equals(field)) {
                    featuresSeen = true;
                    if (p.currentToken() != JsonToken.START_ARRAY) {
                        throw new GeoJsonFormatException("Поле features не является массивом");
                    }
                    while (p.nextToken() != JsonToken.END_ARRAY) {
                        RawFeature feature = readFeature(p);
                        if (feature != null) {
                            consumer.accept(feature);
                            count++;
                        }
                    }
                } else {
                    p.skipChildren();
                }
            }
            if (!featuresSeen) {
                throw new GeoJsonFormatException(
                        "В файле нет поля features — ожидается FeatureCollection");
            }
        }
        return count;
    }

    private RawFeature readFeature(JsonParser p) throws IOException {
        if (p.currentToken() != JsonToken.START_OBJECT) {
            p.skipChildren();
            return null;
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        Geometry geometry = null;

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            switch (field == null ? "" : field) {
                case "geometry":
                    geometry = readGeometry(p);
                    break;
                case "properties":
                    readProperties(p, properties);
                    break;
                default:
                    p.skipChildren();
            }
        }
        return new RawFeature(properties, geometry);
    }

    private void readProperties(JsonParser p, Map<String, Object> out) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_NULL) {
            return;
        }
        if (p.currentToken() != JsonToken.START_OBJECT) {
            p.skipChildren();
            return;
        }
        while (p.nextToken() != JsonToken.END_OBJECT) {
            String key = p.currentName();
            JsonToken t = p.nextToken();
            switch (t) {
                case VALUE_STRING:
                    out.put(key, p.getText());
                    break;
                case VALUE_NUMBER_INT:
                    out.put(key, p.getLongValue());
                    break;
                case VALUE_NUMBER_FLOAT:
                    out.put(key, p.getDoubleValue());
                    break;
                case VALUE_TRUE:
                case VALUE_FALSE:
                    out.put(key, p.getBooleanValue());
                    break;
                case VALUE_NULL:
                    out.put(key, null);
                    break;
                case START_ARRAY:
                    out.put(key, readScalarArray(p));
                    break;
                default:
                    // Вложенные объекты в атрибутах кейсом не используются — пропускаем.
                    p.skipChildren();
            }
        }
    }

    private List<Object> readScalarArray(JsonParser p) throws IOException {
        List<Object> list = new ArrayList<>();
        while (p.nextToken() != JsonToken.END_ARRAY) {
            switch (p.currentToken()) {
                case VALUE_STRING:
                    list.add(p.getText());
                    break;
                case VALUE_NUMBER_INT:
                    list.add(p.getLongValue());
                    break;
                case VALUE_NUMBER_FLOAT:
                    list.add(p.getDoubleValue());
                    break;
                default:
                    p.skipChildren();
            }
        }
        return list;
    }

    // =================================================================================
    //  Геометрия
    // =================================================================================

    private Geometry readGeometry(JsonParser p) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_NULL) {
            // Сводная запись варианта приходит с geometry = null (раздел 7.1 ТП).
            return null;
        }
        if (p.currentToken() != JsonToken.START_OBJECT) {
            p.skipChildren();
            return null;
        }
        String type = null;
        Object coords = null;
        List<Geometry> collection = null;

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String field = p.currentName();
            p.nextToken();
            if ("type".equals(field)) {
                type = p.getValueAsString();
            } else if ("coordinates".equals(field)) {
                coords = readCoordinateTree(p);
            } else if ("geometries".equals(field)) {
                collection = new ArrayList<>();
                while (p.nextToken() != JsonToken.END_ARRAY) {
                    Geometry g = readGeometry(p);
                    if (g != null) {
                        collection.add(g);
                    }
                }
            } else {
                p.skipChildren();
            }
        }
        if (type == null) {
            return null;
        }
        if (collection != null) {
            return Geo.FACTORY.createGeometryCollection(collection.toArray(new Geometry[0]));
        }
        return build(type, coords);
    }

    /**
     * Дерево координат читается один раз и сразу в проекции: списки {@code Double}
     * заменяются на {@link Coordinate}, поэтому вложенность уменьшается на один уровень
     * и объём в памяти — примерно вдвое.
     */
    private Object readCoordinateTree(JsonParser p) throws IOException {
        if (p.currentToken() != JsonToken.START_ARRAY) {
            return null;
        }
        // Заглядываем внутрь: число — значит это сама пара координат.
        JsonToken t = p.nextToken();
        if (t == JsonToken.VALUE_NUMBER_INT || t == JsonToken.VALUE_NUMBER_FLOAT) {
            double lon = p.getDoubleValue();
            p.nextToken();
            double lat = p.getDoubleValue();
            Double z = null;
            while (p.nextToken() != JsonToken.END_ARRAY) {
                if (z == null) {
                    z = p.getDoubleValue();
                }
            }
            Coordinate c = projection.project(lon, lat);
            if (z != null) {
                c.setZ(z);
            }
            return c;
        }
        List<Object> list = new ArrayList<>();
        while (t != JsonToken.END_ARRAY) {
            list.add(readCoordinateTree(p));
            t = p.nextToken();
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private Geometry build(String type, Object coords) {
        if (coords == null) {
            return null;
        }
        switch (type) {
            case "Point":
                return Geo.FACTORY.createPoint((Coordinate) coords);
            case "MultiPoint":
                return Geo.FACTORY.createMultiPoint(
                        toCoordinateArray((List<Object>) coords));
            case "LineString":
                return Geo.FACTORY.createLineString(toCoordinateArray((List<Object>) coords));
            case "MultiLineString": {
                List<Object> parts = (List<Object>) coords;
                LineString[] lines = new LineString[parts.size()];
                for (int i = 0; i < parts.size(); i++) {
                    lines[i] = Geo.FACTORY.createLineString(
                            toCoordinateArray((List<Object>) parts.get(i)));
                }
                return Geo.FACTORY.createMultiLineString(lines);
            }
            case "Polygon":
                return buildPolygon((List<Object>) coords);
            case "MultiPolygon": {
                List<Object> parts = (List<Object>) coords;
                Polygon[] polygons = new Polygon[parts.size()];
                for (int i = 0; i < parts.size(); i++) {
                    polygons[i] = buildPolygon((List<Object>) parts.get(i));
                }
                return Geo.FACTORY.createMultiPolygon(polygons);
            }
            default:
                throw new GeoJsonFormatException("Неизвестный тип геометрии: " + type);
        }
    }

    @SuppressWarnings("unchecked")
    private Polygon buildPolygon(List<Object> rings) {
        if (rings.isEmpty()) {
            return Geo.FACTORY.createPolygon();
        }
        LinearRing shell = ring((List<Object>) rings.get(0));
        LinearRing[] holes = new LinearRing[Math.max(0, rings.size() - 1)];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = ring((List<Object>) rings.get(i));
        }
        return Geo.FACTORY.createPolygon(shell, holes);
    }

    private LinearRing ring(List<Object> coords) {
        Coordinate[] arr = toCoordinateArray(coords);
        if (arr.length > 0 && !arr[0].equals2D(arr[arr.length - 1])) {
            // Незамкнутое кольцо встречается в выгрузках; JTS такое не принимает.
            Coordinate[] closed = new Coordinate[arr.length + 1];
            System.arraycopy(arr, 0, closed, 0, arr.length);
            closed[arr.length] = arr[0].copy();
            arr = closed;
        }
        return Geo.FACTORY.createLinearRing(arr);
    }

    private Coordinate[] toCoordinateArray(List<Object> list) {
        Coordinate[] arr = new Coordinate[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = (Coordinate) list.get(i);
        }
        return arr;
    }
}
