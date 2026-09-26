package ru.lct.heatroute.domain.model;

import lombok.Builder;
import lombok.Value;
import lombok.With;
import org.locationtech.jts.geom.Point;

/** Существующая тепловая камера (таблица 2.2 ТП). */
@Value
@Builder
public class ExistingChamber {

    String id;

    /** Положение в рабочей проекции (EPSG:32637). */
    Point location;

    /**
     * Входное значение условного диаметра камеры — максимальный ДУ уже примыкающих
     * к ней существующих участков, мм. Именно оно считается исходным при проверке
     * правила четырёх примыканий (раздел 2.1 ТП, разъяснение №12).
     */
    @With
    int diameter;

    /** {@code true}, если ДУ камеры не был передан и восстановлен по примыкающим участкам. */
    @With
    boolean diameterInferred;

    /** ID следующего существующего объекта по направлению к источнику. */
    @With
    String upstreamObjectId;

    @With
    boolean upstreamInferred;

    /** Число участков существующей сети, уже примыкающих к камере. */
    @With
    int existingDegree;
}
