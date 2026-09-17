package ru.lct.heatroute.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.util.Map;
import java.util.UUID;

/** Вариант подключения в списке результатов расчёта. */
@Value
@Builder
@Schema(description = "Вариант подключения")
public class VariantResponse {

    UUID id;

    @Schema(description = "Идентификатор варианта в выгрузке", example = "v2")
    String variantCode;

    @Schema(description = "Чем этот вариант отличается от остальных")
    String description;

    VariantSummaryDto summary;

    @Schema(description = "Число выходных объектов по типам")
    Map<String, Long> featureCounts;
}
