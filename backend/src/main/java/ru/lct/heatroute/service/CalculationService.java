package ru.lct.heatroute.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lct.heatroute.api.dto.CreateJobRequest;
import ru.lct.heatroute.api.dto.JobResponse;
import ru.lct.heatroute.api.dto.JobStatsDto;
import ru.lct.heatroute.api.dto.VariantResponse;
import ru.lct.heatroute.api.dto.VariantSummaryDto;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.persistence.CalculationJobEntity;
import ru.lct.heatroute.persistence.CalculationJobRepository;
import ru.lct.heatroute.persistence.DatasetEntity;
import ru.lct.heatroute.persistence.DatasetStatus;
import ru.lct.heatroute.persistence.JobStatus;
import ru.lct.heatroute.persistence.VariantEntity;
import ru.lct.heatroute.persistence.VariantFeatureRepository;
import ru.lct.heatroute.persistence.VariantRepository;
import ru.lct.heatroute.variant.VariantPlanner;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Запуск расчёта и выдача его результата.
 * <p>
 * Расчёт идёт фоновой задачей: на конкурсном наборе он занимает около двадцати секунд,
 * и держать на нём HTTP-соединение нельзя. Требование ТЗ о пятидесяти пользователях
 * означает, что долгий запрос одного не должен занимать поток другого, поэтому клиент
 * получает идентификатор сразу, а состояние и прогресс запрашивает отдельно.
 */
@Slf4j
@Service
@Profile("!nodb")
public class CalculationService {

    private final DatasetService datasets;
    private final VariantPlanner planner;
    private final SceneMapper mapper;
    private final JobStateWriter state;
    private final CalculationJobRepository jobs;
    private final VariantRepository variants;
    private final VariantFeatureRepository variantFeatures;
    private final ObjectMapper json;

    public CalculationService(DatasetService datasets,
                              VariantPlanner planner,
                              SceneMapper mapper,
                              JobStateWriter state,
                              CalculationJobRepository jobs,
                              VariantRepository variants,
                              VariantFeatureRepository variantFeatures,
                              ObjectMapper json) {
        this.datasets = datasets;
        this.planner = planner;
        this.mapper = mapper;
        this.state = state;
        this.jobs = jobs;
        this.variants = variants;
        this.variantFeatures = variantFeatures;
        this.json = json;
    }

    // =================================================================================
    //  Постановка задачи
    // =================================================================================

    @Transactional
    public JobResponse submit(CreateJobRequest request) {
        DatasetEntity dataset = datasets.require(request.getDatasetId());
        if (dataset.getStatus() == DatasetStatus.FAILED) {
            throw new IllegalStateException(
                    "Набор не разобран, расчёт невозможен: " + dataset.getErrorMessage());
        }

        CalculationJobEntity job = new CalculationJobEntity();
        job.setId(UUID.randomUUID());
        job.setDataset(dataset);
        job.setStatus(JobStatus.QUEUED);
        job.setProgress(0);
        job.setStage("В очереди");
        job.setCreatedAt(OffsetDateTime.now());
        job.setParamsJson(write(request));
        jobs.save(job);

        log.info("Расчёт {} поставлен в очередь по набору {}", job.getId(), dataset.getId());
        return toResponse(job, List.of());
    }

    // =================================================================================
    //  Исполнение
    // =================================================================================

    /** Фоновое исполнение. Вызывается контроллером сразу после постановки в очередь. */
    @Async("calculationExecutor")
    @Transactional(readOnly = true)
    public void execute(UUID jobId) {
        CalculationJobEntity job = jobs.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != JobStatus.QUEUED) {
            return;
        }
        state.progress(jobId, JobStatus.RUNNING, 0.01, "Чтение исходных данных");
        long started = System.nanoTime();

        try {
            InputScene scene = datasets.parse(job.getDataset());
            state.progress(jobId, JobStatus.RUNNING, 0.03, "Исходные данные разобраны");

            VariantPlanner.Plan plan = planner.plan(scene,
                    (fraction, stage) -> state.progress(jobId, JobStatus.RUNNING, fraction, stage));

            state.saveResult(jobId, plan);
            state.complete(jobId, plan, Duration.ofNanos(System.nanoTime() - started));
            log.info("Расчёт {} завершён за {} мс: вариантов {}",
                    jobId, plan.getMillis(), plan.getVariants().size());
        } catch (RuntimeException e) {
            log.error("Расчёт {} прерван ошибкой", jobId, e);
            state.fail(jobId, e);
        }
    }

    // =================================================================================
    //  Чтение
    // =================================================================================

    @Transactional(readOnly = true)
    public JobResponse get(UUID jobId) {
        return toResponse(require(jobId), variants.findByJobIdOrderByRankAsc(jobId));
    }

    @Transactional(readOnly = true)
    public List<JobResponse> list(Pageable pageable) {
        return jobs.findAllByOrderByCreatedAtDesc(pageable).getContent().stream()
                .map(job -> toResponse(job, List.of()))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public CalculationJobEntity require(UUID jobId) {
        return jobs.findById(jobId).orElseThrow(() ->
                new DatasetService.NotFoundException("Расчёт не найден: " + jobId));
    }

    @Transactional
    public void delete(UUID jobId) {
        jobs.delete(require(jobId));
    }

    // =================================================================================

    private JobResponse toResponse(CalculationJobEntity job, List<VariantEntity> variantEntities) {
        Long duration = job.getStartedAt() == null || job.getFinishedAt() == null ? null
                : Duration.between(job.getStartedAt(), job.getFinishedAt()).toMillis();

        return JobResponse.builder()
                .id(job.getId())
                .datasetId(job.getDataset().getId())
                .status(job.getStatus().name())
                .progress(job.getProgress())
                .stage(job.getStage())
                .createdAt(job.getCreatedAt())
                .startedAt(job.getStartedAt())
                .finishedAt(job.getFinishedAt())
                .durationMillis(duration)
                .errorMessage(job.getErrorMessage())
                .stats(read(job.getStatsJson(), JobStatsDto.class))
                .variants(variantEntities.stream().map(this::toVariant).collect(Collectors.toList()))
                .build();
    }

    private VariantResponse toVariant(VariantEntity entity) {
        Map<String, Long> counts = new LinkedHashMap<>();
        variantFeatures.findByVariantIdOrderByOrdinalAsc(entity.getId())
                .forEach(f -> counts.merge(f.getObjectType(), 1L, Long::sum));

        return VariantResponse.builder()
                .id(entity.getId())
                .variantCode(entity.getVariantCode())
                .description(entity.getDescription())
                .summary(read(entity.getSummaryJson(), VariantSummaryDto.class))
                .featureCounts(counts)
                .build();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сериализовать параметры расчёта", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        if (value == null) {
            return null;
        }
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            log.warn("Не удалось прочитать сохранённые данные расчёта: {}", e.getMessage());
            return null;
        }
    }
}
