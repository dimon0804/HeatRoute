package ru.lct.heatroute.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Geometry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.heatroute.export.GeoJsonResultWriter;
import ru.lct.heatroute.export.WorkStatementWriter;
import ru.lct.heatroute.export.ResultFeatureFactory.ResultFeature;
import ru.lct.heatroute.persistence.CalculationJobEntity;
import ru.lct.heatroute.persistence.CalculationJobRepository;
import ru.lct.heatroute.persistence.VariantEntity;
import ru.lct.heatroute.persistence.VariantFeatureEntity;
import ru.lct.heatroute.persistence.VariantFeatureRepository;
import ru.lct.heatroute.persistence.VariantRepository;

import java.io.IOException;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
    private final VariantRepository variants;
    private final CalculationJobRepository jobs;
    private final GeoJsonResultWriter writer;
    private final WorkStatementWriter statement;
    private final ObjectMapper json;

    public ResultExportService(VariantFeatureRepository features,
                               VariantRepository variants,
                               CalculationJobRepository jobs,
                               GeoJsonResultWriter writer,
                               WorkStatementWriter statement,
                               ObjectMapper json) {
        this.features = features;
        this.variants = variants;
        this.jobs = jobs;
        this.writer = writer;
        this.statement = statement;
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

    /**
     * Ведомость объёмов работ по одному варианту.
     * <p>
     * В отличие от выгрузки GeoJSON строится в памяти: ведомость читает человек,
     * и счёт в ней идёт на сотни строк, а не на сотни мегабайт. Вариант берётся
     * по коду; если код не задан, берётся лучший — тот, что занял первое место.
     */
    @Transactional(readOnly = true)
    public void streamStatement(UUID jobId, String variantCode, OutputStream out)
            throws IOException {
        CalculationJobEntity job = jobs.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Расчёт не найден: " + jobId));
        List<VariantEntity> all = variants.findByJobIdOrderByRankAsc(jobId);
        if (all.isEmpty()) {
            throw new IllegalStateException("У расчёта нет вариантов: ведомость не построить");
        }
        VariantEntity variant = all.stream()
                .filter(v -> variantCode == null || variantCode.isEmpty()
                        || variantCode.equals(v.getVariantCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Вариант не найден: " + variantCode));

        List<WorkStatementWriter.Row> rows = features
                .findByVariantIdOrderByOrdinalAsc(variant.getId()).stream()
                .filter(row -> !"variant_summary".equals(row.getObjectType()))
                .map(row -> new WorkStatementWriter.Row(
                        row.getObjectType(), row.getFeatureId(), properties(row)))
                .collect(java.util.stream.Collectors.toList());

        WorkStatementWriter.Header header = new WorkStatementWriter.Header(
                job.getDataset() == null ? "" : job.getDataset().getOriginalName(),
                variant.getVariantCode(),
                variant.getRank(),
                variant.getDescription(),
                variant.getScore(),
                OffsetDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")));

        statement.write(out, header, rows);
        log.info("Ведомость по расчёту {} варианту {}: строк {}",
                jobId, variant.getVariantCode(), rows.size());
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
