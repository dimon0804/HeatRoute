package ru.lct.heatroute.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Geometry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.heatroute.export.GeoJsonResultWriter;
import ru.lct.heatroute.export.ResultFeatureFactory.ResultFeature;
import ru.lct.heatroute.persistence.VariantFeatureEntity;
import ru.lct.heatroute.persistence.VariantFeatureRepository;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Выдача результата расчёта одним файлом GeoJSON.
 * <p>
 * Объекты читаются из базы курсором и уходят в поток ответа по одному: требование
 * раздела 3.2 ТЗ — выгрузка до 500 МБ, а собирать такой документ в памяти нельзя.
 * Порядок объектов воспроизводит порядок выгрузки, зафиксированный при сохранении.
 */
@Slf4j
@Service
@Profile("!nodb")
public class ResultExportService {

    private final VariantFeatureRepository features;
    private final GeoJsonResultWriter writer;
    private final ObjectMapper json;

    public ResultExportService(VariantFeatureRepository features,
                               GeoJsonResultWriter writer,
                               ObjectMapper json) {
        this.features = features;
        this.writer = writer;
        this.json = json;
    }

    /**
     * Пишет результат расчёта в поток. Транзакция нужна на всё время записи:
     * курсор по строкам живёт внутри неё.
     */
    @Transactional(readOnly = true)
    public void streamResult(UUID jobId, OutputStream out) throws IOException {
        try (Stream<VariantFeatureEntity> rows = features.streamByJob(jobId)) {
            writer.writeCollection(out, sink -> rows.forEach(row -> sink.accept(toFeature(row))));
        }
    }

    private ResultFeature toFeature(VariantFeatureEntity row) {
        return new ResultFeature(
                row.getObjectType(),
                row.getFeatureId(),
                geometry(row),
                properties(row));
    }

    private Geometry geometry(VariantFeatureEntity row) {
        Geometry geometry = row.getGeom();
        if (geometry != null) {
            geometry.setSRID(4326);
        }
        return geometry;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(VariantFeatureEntity row) {
        try {
            return json.readValue(row.getPropertiesJson(), LinkedHashMap.class);
        } catch (Exception e) {
            log.warn("Не удалось прочитать атрибуты объекта {}: {}",
                    row.getFeatureId(), e.getMessage());
            return new LinkedHashMap<>();
        }
    }
}
