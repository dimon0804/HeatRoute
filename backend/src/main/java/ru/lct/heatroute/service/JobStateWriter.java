package ru.lct.heatroute.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.heatroute.api.dto.JobStatsDto;
import ru.lct.heatroute.domain.result.CalculationVariant;
import ru.lct.heatroute.export.ResultFeatureFactory;
import ru.lct.heatroute.persistence.CalculationJobEntity;
import ru.lct.heatroute.persistence.CalculationJobRepository;
import ru.lct.heatroute.persistence.JobStatus;
import ru.lct.heatroute.persistence.VariantEntity;
import ru.lct.heatroute.persistence.VariantFeatureEntity;
import ru.lct.heatroute.persistence.VariantFeatureRepository;
import ru.lct.heatroute.persistence.VariantRepository;
import ru.lct.heatroute.variant.VariantPlanner;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Запись состояния расчёта в собственных транзакциях.
 * <p>
 * Вынесено в отдельный компонент не ради слоёв: Spring применяет {@code @Transactional}
 * через прокси, поэтому вызов такого метода из соседнего метода того же бина проходит
 * мимо перехватчика. Отметки прогресса тогда попадали бы в базу только вместе с общей
 * транзакцией расчёта — то есть после его завершения, когда они уже никому не нужны.
 */
@Slf4j
@Service
@Profile("!nodb")
public class JobStateWriter {

    /** Размер пачки при сохранении объектов результата. */
    private static final int BATCH_SIZE = 500;

    private final CalculationJobRepository jobs;
    private final VariantRepository variants;
    private final VariantFeatureRepository variantFeatures;
    private final ResultFeatureFactory features;
    private final SceneMapper mapper;
    private final ObjectMapper json;

    /**
     * Отдельный сериализатор для атрибутов выходных объектов: пустые значения в нём
     * сохраняются. Общая настройка сервиса выбрасывает {@code null} из ответов API,
     * и это правильно для API, но для выгрузки — нет.
     */
    private final ObjectMapper exportJson;

    public JobStateWriter(CalculationJobRepository jobs,
                          VariantRepository variants,
                          VariantFeatureRepository variantFeatures,
                          ResultFeatureFactory features,
                          SceneMapper mapper,
                          ObjectMapper json) {
        this.jobs = jobs;
        this.variants = variants;
        this.variantFeatures = variantFeatures;
        this.features = features;
        this.mapper = mapper;
        this.json = json;
        this.exportJson = json.copy()
                .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void progress(UUID jobId, JobStatus status, double fraction, String stage) {
        jobs.findById(jobId).ifPresent(job -> {
            job.setStatus(status);
            job.setProgress(Math.max(0, Math.min(1, fraction)));
            job.setStage(stage);
            if (status == JobStatus.RUNNING && job.getStartedAt() == null) {
                job.setStartedAt(OffsetDateTime.now());
            }
            jobs.save(job);
        });
    }

    /** Сохранение вариантов и их объектов пачками. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveResult(UUID jobId, VariantPlanner.Plan plan) {
        CalculationJobEntity job = jobs.findById(jobId).orElseThrow();

        for (CalculationVariant variant : plan.getVariants()) {
            VariantEntity entity = new VariantEntity();
            entity.setId(UUID.randomUUID());
            entity.setJob(job);
            entity.setVariantCode(variant.getVariantId());
            entity.setRank(variant.getSummary().getRank());
            entity.setDescription(variant.getDescription());
            entity.setSummaryJson(write(mapper.summary(variant.getSummary())));
            entity.setFingerprint(variant.getStructureFingerprint());
            entity.setScore(variant.getSummary().getScore());
            entity.setCalculatedCost(variant.getSummary().getCalculatedCost());
            entity.setTotalLength(variant.getSummary().getNewNetworkLength());
            variants.save(entity);

            List<VariantFeatureEntity> batch = new ArrayList<>(BATCH_SIZE);
            int[] ordinal = {0};
            features.forEachWithDiagnostics(variant, feature -> {
                VariantFeatureEntity row = new VariantFeatureEntity();
                row.setVariant(entity);
                row.setObjectType(feature.getObjectType());
                row.setFeatureId(feature.getId());
                row.setOrdinal(ordinal[0]++);
                row.setGeom(feature.getGeometry());
                row.setPropertiesJson(write(feature.getProperties()));
                batch.add(row);
                if (batch.size() >= BATCH_SIZE) {
                    variantFeatures.saveAll(batch);
                    batch.clear();
                }
            });
            if (!batch.isEmpty()) {
                variantFeatures.saveAll(batch);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID jobId, VariantPlanner.Plan plan, Duration duration) {
        jobs.findById(jobId).ifPresent(job -> {
            job.setStatus(JobStatus.COMPLETED);
            job.setProgress(1);
            job.setStage("Расчёт завершён");
            job.setFinishedAt(OffsetDateTime.now());
            job.setStatsJson(write(JobStatsDto.builder()
                    .designDiameter(plan.getDesignDiameter())
                    .graphNodes(plan.getGraphNodes())
                    .graphEdges(plan.getGraphEdges())
                    .tieInCandidates(plan.getTieInCandidates())
                    .millis(duration.toMillis())
                    .sharpTurns(plan.getSharpTurns())
                    .verifiedMoves(plan.getVerifiedMoves())
                    .depthUnresolved(plan.getDepthUnresolvedByVariant().values().stream()
                            .mapToInt(java.util.List::size).sum())
                    .build()));
            jobs.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID jobId, Exception error) {
        jobs.findById(jobId).ifPresent(job -> {
            job.setStatus(JobStatus.FAILED);
            job.setStage("Расчёт прерван");
            job.setFinishedAt(OffsetDateTime.now());
            job.setErrorMessage(error.getClass().getSimpleName() + ": " + error.getMessage());
            jobs.save(job);
        });
    }

    /**
     * Сериализация атрибутов выходного объекта для хранения в базе.
     * <p>
     * Своя настройка, а не общая: сервис отдаёт API с {@code non_null}, чтобы в ответах
     * не было пустых полей, — но состав атрибутов выгрузки задан приложением жёстко,
     * и {@code depth_start} с {@code depth_end} в плоском расчёте обязаны присутствовать
     * именно со значением {@code null}. С общей настройкой они пропадали при записи
     * в базу и не доходили до выходного файла.
     */
    private String write(Object value) {
        try {
            return exportJson.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сериализовать результат расчёта", e);
        }
    }
}
