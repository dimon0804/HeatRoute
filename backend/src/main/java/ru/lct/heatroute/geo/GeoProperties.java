package ru.lct.heatroute.geo;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Параметры геометрической части: проекция и допуски сшивания. */
@Data
@ConfigurationProperties(prefix = "heatroute.geo")
public class GeoProperties {

    /**
     * Зона UTM рабочей проекции. Техническое приложение фиксирует EPSG:32637,
     * то есть зону 37. Значение вынесено в параметр, чтобы набор из другого региона
     * обрабатывался без правки кода.
     */
    private int utmZone = 37;

    private boolean northernHemisphere = true;

    /**
     * Допуск, с которым концы существующих участков считаются одним узлом, м.
     * В конкурсном наборе концы совпадают точно, но в реальных выгрузках расходятся
     * на сантиметры, и без допуска сеть распадается на несвязные куски.
     */
    private double snapTolerance = 0.5;

    /** Допуск упрощения контуров препятствий перед построением графа видимости, м. */
    private double simplifyTolerance = 0.25;

    /** Число сегментов на четверть окружности при буферизации препятствий. */
    private int bufferQuadrantSegments = 2;

    /** Предельное отклонение при проверке «точка лежит на линии», м. */
    private double onLineTolerance = 0.05;
}
