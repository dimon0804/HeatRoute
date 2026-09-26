package ru.lct.heatroute.domain.result;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Point;

/** Новая тепловая камера (раздел 7.2 ТП). */
@Value
@Builder(toBuilder = true)
public class NewChamberResult {

    String id;
    String variantId;
    Point location;

    /** Наибольший условный диаметр примыкающих участков в итоговом варианте, мм. */
    int diameter;

    /** Число примыкающих участков: не более четырёх (раздел 3 ТП). */
    int degree;

    /** Стоимость строительства камеры, руб. */
    double cost;
}
