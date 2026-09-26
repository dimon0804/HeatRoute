package ru.lct.heatroute.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/** Отчёт о соответствии выгрузки техническому приложению. */
@Value
@Builder
@Schema(description = "Отчёт о соответствии выгрузки техническому приложению")
public class ComplianceReportDto {

    @Schema(description = "Выгрузка соответствует правилам приложения")
    boolean compliant;

    @Schema(description = "Сколько отдельных сверок выполнено", example = "1526")
    int checks;

    @Schema(description = "Сколько нарушений найдено", example = "0")
    int violations;

    @Schema(description = "Идентификаторы вариантов, найденных в выгрузке")
    List<String> variantIds;

    @Schema(description = "Сколько объектов каждого типа найдено в выгрузке")
    Map<String, Long> objectCounts;

    @Schema(description = "Перечень нарушений с указанием объектов")
    List<Finding> findings;

    @Schema(description = "Правила, которые проверить не удалось, и почему")
    List<String> skipped;

    /** Одно нарушение с формулировкой требования, по которой его можно сверить. */
    @Value
    @Builder
    @Schema(description = "Нарушение правила приложения")
    public static class Finding {

        @Schema(description = "Код правила", example = "RUN_LENGTH_LIMIT")
        String rule;

        @Schema(description = "Что проверяет правило")
        String title;

        @Schema(description = "Формулировка требования приложения")
        String requirement;

        @Schema(description = "Вариант, к которому относится нарушение")
        String variantId;

        @Schema(description = "Объекты, которыми нарушение подтверждается")
        List<String> objectIds;

        @Schema(description = "Что именно не сошлось, с числами")
        String detail;
    }
}
