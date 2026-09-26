package ru.lct.heatroute.depth;

import lombok.Value;
import org.locationtech.jts.geom.Coordinate;

/**
 * Пересечение новой тепловой сети с существующей коммуникацией по глубине.
 * <p>
 * Раздел 7 приложения по глубине требует показать в результате места пересечений,
 * прохождение сверху или снизу и расчётные вертикальные расстояния, поэтому
 * пересечение — самостоятельный объект результата, а не промежуточная величина.
 */
@Value
public class UtilityCrossing {

    /** Где пересечение: расстояние от начала участка, м. */
    double station;

    Coordinate location;

    /** ID участка новой сети. */
    String segmentId;

    /** ID пересекаемого объекта. */
    String utilityId;

    /** Тип пересекаемой коммуникации по разделе 4 ТП. */
    String utilityType;

    /** Глубина до верха габарита существующей коммуникации, м. */
    double utilityDepthToTop;

    /** Высота расчётного габарита существующей коммуникации, м. */
    double utilityHeight;

    /** Требуемый вертикальный просвет между габаритами, м. */
    double requiredClearance;

    /** Новая сеть проходит выше или ниже коммуникации. */
    Passage passage;

    /** Глубина новой сети до верха её габарита в месте пересечения, м. */
    double newDepth;

    /** Фактический вертикальный просвет между габаритами, м. */
    double actualClearance;

    public enum Passage {
        /** Новая сеть проходит выше коммуникации. */
        ABOVE,
        /** Новая сеть проходит ниже коммуникации. */
        BELOW
    }

    public String passageLabel() {
        return passage == Passage.ABOVE ? "сверху" : "снизу";
    }
}
