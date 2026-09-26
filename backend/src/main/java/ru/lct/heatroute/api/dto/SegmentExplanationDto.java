package ru.lct.heatroute.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/** Разбор одного участка: чем задано его место и насколько свободно он лежит. */
@Value
@Builder
@Schema(description = "Почему участок трассы прошёл именно здесь")
public class SegmentExplanationDto {

    @Schema(description = "Идентификатор участка в выгрузке", example = "new_v1_5")
    String segmentId;

    @Schema(description = "Условный диаметр участка, мм", example = "300")
    int diameter;

    @Schema(description = "Длина участка, м", example = "165.92")
    double lengthM;

    @Schema(description = "Прямое расстояние между концами участка, м", example = "160.1")
    double straightM;

    @Schema(description = "Насколько участок длиннее прямой, доля", example = "0.036")
    double detourShare;

    @Schema(description = "Наименьший запас до ограничения по всему участку, м",
            example = "0.12")
    Double tightestMarginM;

    @Schema(description = "Ограничения рядом, от самого зажимающего")
    List<Nearby> nearby;

    @Schema(description = "Специальные проходы, через которые идёт участок")
    List<Crossing> crossings;

    @Schema(description = "Короткий вывод: чем участок зажат и насколько свободно лежит")
    String verdict;

    /** Ограничение рядом с участком. */
    @Value
    @Builder
    @Schema(description = "Ограничение рядом с участком")
    public static class Nearby {

        @Schema(description = "Идентификатор ограничения во входных данных")
        String restrictionId;

        @Schema(description = "Тип ограничения после разрешения псевдонима")
        String type;

        @Schema(description = "Правило приложения: обойти или специальный проход")
        String rule;

        @Schema(description = "Фактическое расстояние от оси трассы до объекта, м")
        double distanceM;

        @Schema(description = "Минимальное расстояние от оси, требуемое приложением, м")
        double requiredM;

        @Schema(description = "Запас: сколько ещё можно было бы подвинуться, м")
        double marginM;

        @Schema(description = "Именно это ограничение задало место участка")
        boolean binding;

        @Schema(description = "Участок примыкает к объекту: так выглядит место "
                + "присоединения к существующей сети, и запас здесь не считается")
        boolean attachment;
    }

    /** Специальный проход, через который идёт участок. */
    @Value
    @Builder
    @Schema(description = "Специальный проход")
    public static class Crossing {

        @Schema(description = "Идентификатор пересекаемого объекта")
        String restrictionId;

        @Schema(description = "Тип пересекаемого объекта")
        String type;

        @Schema(description = "Коэффициент специального прохода", example = "1.6")
        double kSpecial;

        @Schema(description = "Доля длины участка, занятая проходом", example = "0.31")
        double share;
    }
}
