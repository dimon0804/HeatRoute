package ru.lct.heatroute.domain.reference;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Positive;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Справочник технического приложения в типизированном виде.
 * <p>
 * Наполняется из {@code reference/heatroute-reference.yml} и целиком переопределяется
 * снаружи: проверочный набор может приехать с другими значениями, и подменяют файл,
 * а не пересобирают сервис.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "heatroute.reference")
public class ReferenceProperties {

    /** Таблицы 4.1 и 4.2: ряд условных диаметров. */
    @NotEmpty
    @Valid
    private List<DiameterRow> diameters;

    /** Таблица 8.2: шкала стоимости тепловой камеры. */
    @NotEmpty
    @Valid
    private List<ChamberCostRow> chamberCostScale;

    /** Раздел 8.2: стоимость одной независимой врезки, руб. */
    @Positive
    private long tieInCost = 5_000_000L;

    /** Раздел 8.3: фиксированная часть штрафа за неподключенный ОКС, руб. */
    private double unconnectedPenaltyFixed = 100_000_000d;

    /** Раздел 8.3: переменная часть штрафа, руб. за 1 т/ч расчётного расхода ОКС. */
    private double unconnectedPenaltyPerTph = 500_000d;

    /** Раздел 9: веса и базы приведения показателя ранжирования. */
    @Valid
    private Scoring scoring = new Scoring();

    /** Раздел 8.2: радиус «притягивания» точки врезки к существующей камере, м. */
    private double chamberSnapRadius = 10.0;

    /** Раздел 3: предельное число участков, примыкающих к одной тепловой камере. */
    private int maxChamberDegree = 4;

    /** Раздел 6 приложения по глубине. */
    @Valid
    private Depth depth = new Depth();

    /** Таблица 4.3: условные габариты и глубины существующих коммуникаций. */
    private Map<String, UtilityRow> existingUtilities = new LinkedHashMap<>();

    /** Таблица 5.1: правила по типам пространственных ограничений. */
    private Map<String, RestrictionRow> restrictions = new LinkedHashMap<>();

    /** Сопоставление нестандартных написаний restriction_type с типами таблицы 5.1. */
    private Map<String, String> restrictionAliases = new LinkedHashMap<>();

    /** Правило для типа ограничения, отсутствующего в справочнике. */
    private RestrictionRow unknownRestriction = new RestrictionRow();

    // ---------------------------------------------------------------------------------

    /** Строка таблиц 4.1 и 4.2 по одному условному диаметру. */
    @Data
    public static class DiameterRow {
        /** Условный диаметр, мм. */
        private int dn;
        /** Пропускная способность, т/ч. */
        private double capacityTph;
        /** Предельная длина непрерывной части сети одного ДУ, м. */
        private double maxRunLength;
        /** Стоимость нового строительства, руб./м. */
        private double newCostPerM;
        /** Стоимость реконструкции существующей сети, руб./м. */
        private double reconCostPerM;
        /** Наружный диаметр оболочки, м. */
        private double casingOd;
        /** Просвет между оболочками, м. */
        private double clearance;
        /** Расчётная ширина пары труб, м. */
        private double pairWidth;
        /** Расчётная высота (наружный диаметр оболочки одной трубы), м. */
        private double pairHeight;
    }

    /** Строка шкалы стоимости камеры (таблица 8.2). */
    @Data
    public static class ChamberCostRow {
        private int dnFrom;
        private int dnTo;
        private double cost;
    }

    /** Раздел 9: параметры показателя ранжирования. */
    @Data
    public static class Scoring {
        private double costWeight = 0.7;
        private double lengthWeight = 0.3;
        private double costBase = 25_000_000d;
        private double lengthBase = 100d;
    }

    /** Раздел 6: параметры трассировки с учётом глубины. */
    @Data
    public static class Depth {
        private double normalDepth = 3.0;
        private double minDepth = 0.7;
        private double maxDepth = 6.0;
        private double step = 0.5;
        private double freeDepthThreshold = 3.0;
        private double costPerExtraMeter = 0.10;
        private double maxSlope = 0.10;
        private double crossingFlatLength = 4.0;
        private double crossingFlatHalf = 2.0;
    }

    /** Строка таблицы 4.3: условный габарит существующей коммуникации. */
    @Data
    public static class UtilityRow {
        /** Ширина габарита, м; {@code null} — берётся из таблицы 4.2 по ДУ объекта. */
        private Double width;
        /** Высота габарита, м; {@code null} — берётся из таблицы 4.2 по ДУ объекта. */
        private Double height;
        /** Глубина до верха габарита, м. */
        private double depthToTop;
    }

    /** Строка таблицы 5.1: правило по типу пространственного ограничения. */
    @Data
    public static class RestrictionRow {
        private RestrictionRule rule = RestrictionRule.FORBIDDEN;
        /** Минимальное горизонтальное расстояние, м (габарит-в-габарит). */
        private Double minHorizontalDist;
        /** То же, но зависящее от условного диаметра новой сети. */
        private List<DistanceByDn> minHorizontalByDn;
        /** Минимальный угол пересечения, град. */
        private Double minCrossingAngleDeg;
        private VerticalRule verticalRule;
        /** Верх габарита новой сети не выше этой глубины от поверхности, м. */
        private Double minTopBelowSurface;
        /** Минимальный вертикальный просвет между габаритами, м. */
        private Double minVerticalClearance;
        /** Специальный участок покрывает весь полигон объекта плюс вынос. */
        private boolean specialWithinPolygon;
        /** Вынос границ специального участка за объект (или за точку пересечения), м. */
        private double specialMarginM;
        /** Коэффициент стоимости специального прохода. */
        private double kSpecial = 1.0;
    }

    /** Порог «ДУ не более dnTo → расстояние dist». */
    @Data
    public static class DistanceByDn {
        private int dnTo;
        private double dist;
    }
}
