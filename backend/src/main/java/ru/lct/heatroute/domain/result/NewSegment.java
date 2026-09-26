package ru.lct.heatroute.domain.result;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.LineString;

/** Участок новой тепловой сети (раздел 7.2 ТП). */
@Value
@Builder(toBuilder = true)
public class NewSegment {

    String id;
    String variantId;

    /** Ось участка в рабочей проекции. */
    LineString geometry;

    /** ID узла в начале: точка врезки, камера, технический узел или точка подключения. */
    String startNodeId;

    /** ID узла в конце. */
    String endNodeId;

    /** Расчётный расход участка, т/ч. */
    double flowTph;

    /** Условный диаметр, мм. */
    int diameter;

    /** Длина участка, м. */
    double length;

    LayingMethod layingMethod;

    /** Коэффициент специального прохода, применённый к стоимости; 1,0 для обычного. */
    double kSpecial;

    /** Тип пересекаемого объекта для специального участка; {@code null} для обычного. */
    String specialCrossingType;

    /** Глубина до верха расчётного габарита в начале, м; {@code null} в плоской задаче. */
    Double depthStart;

    /** То же в конце участка. */
    Double depthEnd;

    /** Коэффициент стоимости по глубине; 1,0 в плоской задаче. */
    double kDepth;

    /** Стоимость участка, руб. */
    double cost;
}
