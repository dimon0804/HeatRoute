package ru.lct.heatroute.domain.result;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Point;

/** Точка врезки в существующую сеть (раздел 10.2 ТП). */
@Value
@Builder(toBuilder = true)
public class TieInResult {

    String id;
    String variantId;
    Point location;

    /** ID существующего участка или камеры, в которую выполнена врезка. */
    String existingObjectId;

    /** {@code heat_network} или {@code heat_chamber}. */
    String existingObjectType;

    /** Условный диаметр существующего объекта, мм. */
    int existingDiameter;

    /** Условный диаметр новой сети в точке врезки, мм. */
    int requiredDiameter;

    /** Доля длины существующего участка до точки врезки — для частичной реконструкции. */
    double positionFraction;

    /** Дополнительный расход, вносимый этой врезкой в существующую сеть, т/ч. */
    double addedFlowTph;

    /** Стоимость одной врезки, руб. */
    double cost;
}
