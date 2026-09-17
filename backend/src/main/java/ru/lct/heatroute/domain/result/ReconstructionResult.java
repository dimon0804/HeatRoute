package ru.lct.heatroute.domain.result;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.LineString;

/** Реконструируемая часть существующей тепловой сети (раздел 10.3 ТП). */
@Value
@Builder(toBuilder = true)
public class ReconstructionResult {

    String id;
    String variantId;

    /** Фактическая геометрия реконструируемой части, а не всего исходного участка. */
    LineString geometry;

    /** ID входного объекта heat_network, к которому относится эта часть. */
    String existingObjectId;

    /** Текущий расчётный расход, т/ч. */
    double existingFlowTph;

    /** Суммарный дополнительный расход новых подключений на этой части, т/ч. */
    double addedFlowTph;

    /** Расход после подключения, т/ч. */
    double calculatedFlowTph;

    int existingDiameter;
    int requiredDiameter;

    /** Длина реконструируемой части, м. */
    double length;

    /** Стоимость реконструкции по ставке таблицы 4.1, руб. */
    double cost;
}
