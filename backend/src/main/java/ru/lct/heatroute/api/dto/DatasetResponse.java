package ru.lct.heatroute.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Загруженный конкурсный набор и результат его разбора. */
@Value
@Builder
@Schema(description = "Загруженный входной набор")
public class DatasetResponse {

    UUID id;

    @Schema(description = "Имя файла при загрузке", example = "dataset_lct2026.geojson")
    String originalName;

    long sizeBytes;

    @Schema(description = "Число объектов в файле", example = "144")
    long featureCount;

    OffsetDateTime uploadedAt;

    @Schema(description = "Состояние набора", example = "READY")
    String status;

    String errorMessage;

    SceneSummaryDto summary;

    @Schema(description = "Протокол разбора: восстановленные атрибуты и принятые допущения")
    List<DiagnosticsEntryDto> diagnostics;
}
