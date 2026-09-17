package ru.lct.heatroute.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Состояние расчёта и его результат. */
@Value
@Builder
@Schema(description = "Расчёт вариантов подключения")
public class JobResponse {

    UUID id;

    UUID datasetId;

    @Schema(description = "Состояние расчёта", example = "COMPLETED")
    String status;

    @Schema(description = "Доля выполненного от нуля до единицы", example = "1.0")
    double progress;

    @Schema(description = "Текущий этап", example = "Построение графа маршрутизации")
    String stage;

    OffsetDateTime createdAt;
    OffsetDateTime startedAt;
    OffsetDateTime finishedAt;

    @Schema(description = "Длительность расчёта, мс")
    Long durationMillis;

    String errorMessage;

    JobStatsDto stats;

    @Schema(description = "Найденные варианты, лучший первым")
    List<VariantResponse> variants;
}
