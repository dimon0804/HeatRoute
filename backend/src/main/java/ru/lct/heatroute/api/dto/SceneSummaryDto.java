package ru.lct.heatroute.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.util.Map;

/** Сводка по разобранному набору: что в нём есть и в каком масштабе. */
@Value
@Builder
@Schema(description = "Сводка по разобранному входному набору")
public class SceneSummaryDto {

    @Schema(description = "Число объектов по типам")
    Map<String, Long> objectCounts;

    @Schema(description = "Перспективных ОКС", example = "17")
    int futureOksCount;

    @Schema(description = "Суммарный расчётный расход перспективных ОКС, т/ч", example = "488.72")
    double totalFutureFlowTph;

    @Schema(description = "Суммарная длина существующей тепловой сети, м", example = "1460.3")
    double existingNetworkLength;

    @Schema(description = "Условные диаметры существующей сети и число участков")
    Map<String, Long> existingDiameters;

    @Schema(description = "Типы пространственных ограничений и их число")
    Map<String, Long> restrictionTypes;

    @Schema(description = "Габарит набора в рабочей проекции, м")
    double[] extentMeters;

    @Schema(description = "Границы набора в WGS 84: minLon, minLat, maxLon, maxLat")
    double[] bboxWgs84;
}
