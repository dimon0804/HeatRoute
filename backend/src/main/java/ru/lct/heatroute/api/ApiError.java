package ru.lct.heatroute.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;
import java.util.List;

/** Ответ при ошибке. */
@Value
@Builder
@Schema(description = "Описание ошибки")
public class ApiError {

    @Schema(description = "Код состояния HTTP", example = "404")
    int status;

    @Schema(description = "Краткое обозначение", example = "NOT_FOUND")
    String error;

    @Schema(description = "Что пошло не так, на русском языке")
    String message;

    @Schema(description = "Путь запроса", example = "/api/v1/jobs/0b1c")
    String path;

    OffsetDateTime timestamp;

    @Schema(description = "Подробности по полям запроса, если ошибка в них")
    List<String> details;
}
