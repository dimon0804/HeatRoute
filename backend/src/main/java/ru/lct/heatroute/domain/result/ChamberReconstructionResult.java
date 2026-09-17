package ru.lct.heatroute.domain.result;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Point;

/**
 * Реконструируемая существующая тепловая камера (раздел 10.5 ТП).
 * Передаётся только для камеры, использованной под врезку, и только если требуемый
 * условный диаметр превысил её исходный.
 */
@Value
@Builder(toBuilder = true)
public class ChamberReconstructionResult {

    String id;
    String variantId;
    Point location;

    /** ID входного объекта heat_chamber. */
    String existingObjectId;

    /** Входное значение условного диаметра камеры, мм. */
    int existingDiameter;

    /** Наибольший условный диаметр примыкающих участков в итоговом варианте, мм. */
    int requiredDiameter;

    /** Стоимость реконструкции по шкале раздела 8.2, руб. */
    double cost;
}
