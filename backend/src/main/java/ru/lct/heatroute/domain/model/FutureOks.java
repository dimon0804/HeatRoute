package ru.lct.heatroute.domain.model;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

/**
 * Перспективный ОКС вместе со своей точкой подключения.
 * <p>
 * Техническое приложение кладёт расход на полигон {@code oks_future}, а конкурсный
 * набор 2026 года прислал его прямо на {@code oks_connection_point} и без полигонов
 * вовсе. Модель сводит оба варианта к одной сущности: полигон необязателен, расход
 * берётся из того источника, который фактически есть, с пометкой в диагностике.
 */
@Value
@Builder
public class FutureOks {

    /** ID перспективного ОКС; при отсутствии полигона совпадает с ID точки подключения. */
    String id;

    /** Контур ОКС в рабочей проекции; {@code null}, если полигон во входе не передан. */
    Geometry footprint;

    /** Точка подключения на границе ОКС (обязательна). */
    Point connectionPoint;

    /** ID объекта точки подключения во входных данных. */
    String connectionPointId;

    /** Расчётный расход, т/ч — используется при расчёте новой сети. */
    double flowTph;

    /** Справочная тепловая нагрузка, Гкал/ч; {@code null}, если не передана. */
    Double heatLoad;

    /** Откуда фактически взят расход — для диагностики. */
    FlowSource flowSource;

    public enum FlowSource {
        /** Атрибут {@code flow_tph} полигона {@code oks_future} — штатный путь по ТП. */
        OKS_FUTURE,
        /** Атрибут {@code flow_tph} точки подключения — как в конкурсном наборе 2026. */
        CONNECTION_POINT,
        /** Расход не передан; принят ноль, объект отмечен в диагностике. */
        ASSUMED_ZERO
    }
}
