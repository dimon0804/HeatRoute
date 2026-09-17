package ru.lct.heatroute.domain.model;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Point;

/** Источник тепловой энергии. Ограничение по мощности кейсом не рассматривается. */
@Value
@Builder
public class HeatSource {

    String id;

    /** Положение в рабочей проекции (EPSG:32637). */
    Point location;

    /** Наименование из входных данных, если передано. */
    String name;
}
