package ru.lct.heatroute.domain.model;

import lombok.Builder;
import lombok.Value;
import lombok.With;
import org.locationtech.jts.geom.LineString;

/**
 * Участок существующей тепловой сети (таблица 2.2 ТП).
 * Одна линия представляет пару подающего и обратного трубопроводов одного ДУ.
 */
@Value
@Builder
public class ExistingSegment {

    String id;

    /** Ось участка в рабочей проекции (EPSG:32637). */
    LineString geometry;

    /** Текущий условный диаметр, мм. */
    int diameter;

    /** Текущий расчётный расход, т/ч. */
    double flowTph;

    /**
     * ID следующего существующего объекта по направлению к источнику.
     * Если во входных данных не передан — восстанавливается по геометрии
     * и помечается флагом {@link #upstreamInferred}.
     */
    @With
    String upstreamObjectId;

    /** {@code true}, если {@link #upstreamObjectId} восстановлен, а не пришёл во входе. */
    @With
    boolean upstreamInferred;

    /** {@code true}, если расход не был передан и принят равным нулю. */
    boolean flowAssumed;

    /** Длина участка в рабочей проекции, м. */
    public double length() {
        return geometry.getLength();
    }
}
