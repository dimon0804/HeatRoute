package ru.lct.heatroute.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/** Сводная запись варианта — состав раздела 10.7 ТП. */
@Value
@Builder
@Schema(description = "Итоговые показатели варианта")
public class VariantSummaryDto {

    @Schema(description = "Место в ранжировании, 1 — лучший", example = "1")
    int rank;

    @Schema(description = "Стоимость новых участков тепловой сети, руб.")
    double constructionCost;

    @Schema(description = "Стоимость строительства новых тепловых камер, руб.")
    double chamberConstructionCost;

    @Schema(description = "Суммарная стоимость врезок, руб.")
    double tieInCost;

    @Schema(description = "Стоимость реконструкции существующих линейных участков, руб.")
    double reconstructionCost;

    @Schema(description = "Стоимость реконструкции существующих камер, руб.")
    double chamberReconstructionCost;

    @Schema(description = "Штраф за неподключенные ОКС, руб.")
    double unconnectedPenalty;

    @Schema(description = "Итоговая стоимость варианта, руб.")
    double calculatedCost;

    @Schema(description = "Суммарная длина новых линейных участков, м")
    double newNetworkLength;

    @Schema(description = "Суммарная длина реконструируемых частей, м")
    double reconstructionLength;

    @Schema(description = "Новая сеть плюс реконструкция, м")
    double length;

    @Schema(description = "Показатель ранжирования по разделу 9 ТП", example = "17.535")
    double score;

    @Schema(description = "ID перспективных ОКС, для которых маршрут не найден автоматически")
    List<String> unconnectedOksIds;

    @Schema(description = "Почему каждый ОКС остался без подключения: идентификатор → причина")
    Map<String, String> unconnectedReasons;
}
