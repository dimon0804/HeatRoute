package ru.lct.heatroute.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Запись протокола разбора.
 * <p>
 * Это не журнал отладки: критерий «диагностика данных» проверяется экспертами,
 * и по этим записям на защите объясняется, почему сервис принял именно такие
 * значения там, где входные данные молчали.
 */
@Value
@Builder
@Schema(description = "Запись протокола разбора входных данных")
public class DiagnosticsEntryDto {

    @Schema(description = "Уровень", example = "ASSUMPTION",
            allowableValues = {"INFO", "ASSUMPTION", "WARNING", "ERROR"})
    String severity;

    @Schema(description = "Код для машинной обработки", example = "upstream.inferred")
    String code;

    @Schema(description = "Что произошло и какое решение принято")
    String message;

    @Schema(description = "Идентификаторы объектов, которых касается запись")
    List<String> objectIds;
}
