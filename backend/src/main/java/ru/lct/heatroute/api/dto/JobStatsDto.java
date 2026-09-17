package ru.lct.heatroute.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

/** Показатели прогона — нужны, чтобы объяснить, как получен результат. */
@Value
@Builder
@Schema(description = "Показатели прогона расчёта")
public class JobStatsDto {

    @Schema(description = "Условный диаметр, по которому рассчитаны клиренсы, мм", example = "400")
    int designDiameter;

    @Schema(description = "Узлов в графе маршрутизации", example = "3180")
    int graphNodes;

    @Schema(description = "Рёбер в графе маршрутизации", example = "61843")
    int graphEdges;

    @Schema(description = "Рассмотрено кандидатов точек врезки", example = "78")
    int tieInCandidates;

    @Schema(description = "Время расчёта, мс", example = "20791")
    long millis;
}
