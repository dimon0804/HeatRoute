package ru.lct.heatroute.domain.result;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Point;

/**
 * Технический узел (раздел 10.6 ТП) — служебная точка, в которой меняется условный
 * диаметр, способ прокладки, глубина или другой параметр участка, и которая при этом
 * не является тепловой камерой или точкой врезки.
 */
@Value
@Builder(toBuilder = true)
public class TechnicalNodeResult {

    String id;
    String variantId;
    Point location;

    /** Что именно изменилось в этой точке — для объяснимости результата. */
    String reason;
}
