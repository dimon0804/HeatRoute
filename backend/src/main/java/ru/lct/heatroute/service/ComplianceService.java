package ru.lct.heatroute.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Geometry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.lct.heatroute.compliance.ComplianceChecker;
import ru.lct.heatroute.compliance.ComplianceReport;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.geo.ProjectionService;
import ru.lct.heatroute.ingest.GeoJsonStreamParser;
import ru.lct.heatroute.ingest.RawFeature;
import ru.lct.heatroute.ingest.SceneAssembler;
import ru.lct.heatroute.persistence.CalculationJobEntity;
import ru.lct.heatroute.persistence.CalculationJobRepository;
import ru.lct.heatroute.persistence.VariantFeatureEntity;
import ru.lct.heatroute.persistence.VariantFeatureRepository;
import ru.lct.heatroute.persistence.VariantRepository;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Сбор данных для проверки выгрузки и вызов самой проверки.
 * <p>
 * Два входа, и это принципиально разные случаи. Свой расчёт проверяется по тому,
 * что сохранено в базе: так проверяется ровно тот файл, который уедет заказчику,
 * вместе со всеми превращениями по дороге. Именно на этом пути и нашлись два дефекта,
 * которых не видели тесты, работающие с объектами в памяти.
 * <p>
 * Чужая выгрузка проверяется по файлу. Здесь сервис ничего не знает о том, как файл
 * построен, и это единственный способ проверить подрядчика.
 */
@Slf4j
@Service
@Profile("!nodb")
public class ComplianceService {

    private final ComplianceChecker checker;
    private final GeoJsonStreamParser parser;
    private final SceneAssembler assembler;
    private final ProjectionService projection;
    private final ObjectMapper json;
    private final CalculationJobRepository jobs;
    private final VariantRepository variants;
    private final VariantFeatureRepository features;
    private final DatasetService datasets;

    public ComplianceService(ComplianceChecker checker,
                             GeoJsonStreamParser parser,
                             SceneAssembler assembler,
                             ProjectionService projection,
                             ObjectMapper json,
                             CalculationJobRepository jobs,
                             VariantRepository variants,
                             VariantFeatureRepository features,
                             DatasetService datasets) {
        this.checker = checker;
        this.parser = parser;
        this.assembler = assembler;
        this.projection = projection;
        this.json = json;
        this.jobs = jobs;
        this.variants = variants;
        this.features = features;
        this.datasets = datasets;
    }

    /** Проверка сохранённого результата расчёта вместе с его входным набором. */
    @Transactional(readOnly = true)
    public ComplianceReport checkJob(UUID jobId) {
        CalculationJobEntity job = jobs.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Расчёт не найден: " + jobId));
        if (job.getDataset() == null) {
            throw new IllegalStateException(
                    "У расчёта нет входного набора: проверить выгрузку не с чем");
        }

        List<RawFeature> exported = new ArrayList<>();
        variants.findByJobIdOrderByRankAsc(jobId).forEach(variant ->
                features.findByVariantIdOrderByOrdinalAsc(variant.getId()).stream()
                        .filter(row -> ru.lct.heatroute.export.ResultFeatureFactory
                                .exportedObjectTypes().contains(row.getObjectType()))
                        .map(this::toRawFeature)
                        .forEach(exported::add));

        if (exported.isEmpty()) {
            throw new IllegalStateException("У расчёта нет сохранённых объектов выгрузки");
        }

        InputScene scene = datasets.parse(job.getDataset());
        ComplianceReport report = checker.check(exported, scene);
        log.info("Проверка расчёта {}: сверок {}, нарушений {}",
                jobId, report.getChecks(), report.getFindings().size());
        return report;
    }

    /** Проверка произвольной выгрузки по файлу вместе с её входным набором. */
    public ComplianceReport checkFiles(MultipartFile result, MultipartFile dataset)
            throws IOException {
        List<RawFeature> exported = new ArrayList<>();
        try (InputStream in = result.getInputStream()) {
            parser.parse(in, exported::add);
        }
        if (exported.isEmpty()) {
            throw new IllegalArgumentException(
                    "В выгрузке не нашлось ни одного объекта: проверять нечего");
        }

        SceneAssembler.Collector collector = new SceneAssembler.Collector();
        try (InputStream in = dataset.getInputStream()) {
            parser.parse(in, collector::accept);
        }
        InputScene scene = assembler.assemble(collector);

        ComplianceReport report = checker.check(exported, scene);
        log.info("Проверка загруженной выгрузки {}: сверок {}, нарушений {}",
                result.getOriginalFilename(), report.getChecks(), report.getFindings().size());
        return report;
    }

    /**
     * Строка базы превращается в такой же объект, какой отдаёт разбор файла: атрибуты
     * как есть, геометрия в рабочей проекции. Проверка не должна знать, откуда пришёл
     * объект, иначе двух входов не получится.
     */
    @SuppressWarnings("unchecked")
    private RawFeature toRawFeature(VariantFeatureEntity row) {
        Map<String, Object> properties;
        try {
            properties = json.readValue(row.getPropertiesJson(), LinkedHashMap.class);
        } catch (Exception e) {
            log.warn("Не удалось прочитать атрибуты объекта {}: {}",
                    row.getFeatureId(), e.getMessage());
            properties = new LinkedHashMap<>();
        }
        Geometry geometry = row.getGeom();
        return new RawFeature(properties,
                geometry == null ? null : projection.project(geometry));
    }
}
