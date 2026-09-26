package ru.lct.heatroute.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/** Сводная запись варианта — состав раздела 7.2 ТП в редакции от 18.09. */
@Value
@Builder
@Schema(description = "Итоговые показатели варианта")
public class VariantSummaryDto {

    @Schema(description = "Место в ранжировании, 1 — лучший", example = "1")
    int rank;

    @Schema(description = "Стоимость строительства: участки, новые камеры и врезки, руб.")
    double constructionCost;

    @Schema(description = "Стоимость строительства новых тепловых камер, руб.")
    double chamberConstructionCost;

    @Schema(description = "Количество врезок в существующие тепловые камеры")
    int existingChamberTieInCount;

    @Schema(description = "Стоимость врезок в существующие тепловые камеры, руб.")
    double existingChamberTieInCost;

    @Schema(description = "Штраф за неподключенные ОКС, руб.")
    double unconnectedPenalty;

    @Schema(description = "Итоговая стоимость варианта, руб.")
    double calculatedCost;

    @Schema(description = "Суммарная длина новых участков тепловой сети, м")
    double newNetworkLength;

    @Schema(description = "Показатель ранжирования по разделу 6 ТП", example = "8.412")
    double score;

    @Schema(description = "ID точек подключения, для которых маршрут не найден")
    List<Object> unconnectedOksIds;

    @Schema(description = "Почему каждая точка осталась без подключения: идентификатор → причина")
    Map<String, String> unconnectedReasons;
}
