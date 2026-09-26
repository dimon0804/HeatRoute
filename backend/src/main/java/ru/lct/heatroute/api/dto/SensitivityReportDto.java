package ru.lct.heatroute.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Что держит цену решения: насколько изменится показатель, если убрать одну точку
 * подключения или закрыть одно место присоединения.
 */
@Value
@Builder
@Schema(description = "Анализ чувствительности решения")
public class SensitivityReportDto {

    @Schema(description = "Показатель исходного решения", example = "12.145")
    double baseScore;

    @Schema(description = "Стоимость исходного решения, руб.", example = "248579372")
    double baseCost;

    @Schema(description = "Длина новой сети исходного решения, м", example = "1728.37")
    double baseLength;

    @Schema(description = "Сколько прогонов выполнено", example = "18")
    int runs;

    @Schema(description = "Сколько времени занял анализ, мс", example = "410000")
    long millis;

    @Schema(description = "Вклад каждой точки подключения, от самой дорогой")
    List<Row> points;

    /** Один прогон анализа. */
    @Value
    @Builder
    @Schema(description = "Результат одного прогона анализа чувствительности")
    public static class Row {

        @Schema(description = "Что изменено относительно исходной задачи")
        String change;

        @Schema(description = "Идентификатор объекта, которого касается изменение")
        String objectId;

        @Schema(description = "Показатель после изменения", example = "11.02")
        double score;

        @Schema(description = "Стоимость после изменения, руб.")
        double cost;

        @Schema(description = "Длина новой сети после изменения, м")
        double length;

        @Schema(description = "Насколько подорожало решение из-за этого объекта, руб.")
        double costContribution;

        @Schema(description = "Сколько метров сети приходится на этот объект")
        double lengthContribution;

        @Schema(description = "Точки, оставшиеся без подключения в этом прогоне")
        List<Object> unconnected;
    }
}
