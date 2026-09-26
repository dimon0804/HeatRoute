package ru.lct.heatroute.domain.reference;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.reference.ReferenceProperties.ChamberCostRow;
import ru.lct.heatroute.domain.reference.ReferenceProperties.DiameterRow;
import ru.lct.heatroute.domain.reference.ReferenceProperties.DistanceByDn;
import ru.lct.heatroute.domain.reference.ReferenceProperties.RestrictionRow;
import ru.lct.heatroute.domain.reference.ReferenceProperties.UtilityRow;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Поисковые операции по справочнику технического приложения.
 * <p>
 * Вся арифметика кейса — подбор условного диаметра по расходу, стоимость метра,
 * предельная длина, стоимость камеры, правило по типу ограничения — проходит здесь,
 * чтобы правило было записано ровно один раз и его можно было проверить тестом.
 */
@Slf4j
@Component
public class ReferenceCatalog {

    private final ReferenceProperties props;

    /** Ряд диаметров, отсортированный по возрастанию ДУ. */
    private List<DiameterRow> ordered;

    /** Быстрый доступ по точному значению ДУ. */
    private Map<Integer, DiameterRow> byDn;

    /** Ряд, отсортированный по пропускной способности, для подбора по расходу. */
    private TreeMap<Double, DiameterRow> byCapacity;

    /** Типы ограничений, которых не оказалось в справочнике; собираются для диагностики. */
    private final Set<String> unknownTypesSeen = ConcurrentHashMap.newKeySet();

    public ReferenceCatalog(ReferenceProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        ordered = props.getDiameters().stream()
                .sorted(Comparator.comparingInt(DiameterRow::getDn))
                .collect(Collectors.toList());

        byDn = new LinkedHashMap<>();
        byCapacity = new TreeMap<>();
        for (DiameterRow row : ordered) {
            byDn.put(row.getDn(), row);
            byCapacity.put(row.getCapacityTph(), row);
        }

        // Пропускная способность обязана расти вместе с диаметром, иначе подбор
        // «минимального подходящего ДУ» перестаёт быть однозначным.
        for (int i = 1; i < ordered.size(); i++) {
            DiameterRow prev = ordered.get(i - 1);
            DiameterRow cur = ordered.get(i);
            if (cur.getCapacityTph() <= prev.getCapacityTph()) {
                throw new IllegalStateException(String.format(
                        "Справочник диаметров не монотонен: ДУ %d (%.1f т/ч) не больше ДУ %d (%.1f т/ч)",
                        cur.getDn(), cur.getCapacityTph(), prev.getDn(), prev.getCapacityTph()));
            }
        }

        log.info("Справочник загружен: {} условных диаметров ({}..{} мм), "
                        + "{} правил по ограничениям, {} псевдонимов типов",
                ordered.size(), ordered.get(0).getDn(), ordered.get(ordered.size() - 1).getDn(),
                props.getRestrictions().size(), props.getRestrictionAliases().size());
    }

    public ReferenceProperties props() {
        return props;
    }

    /** Весь ряд диаметров по возрастанию. */
    public List<DiameterRow> diameters() {
        return Collections.unmodifiableList(ordered);
    }

    /** Наибольший условный диаметр справочника. */
    public DiameterRow largest() {
        return ordered.get(ordered.size() - 1);
    }

    // =================================================================================
    //  Подбор условного диаметра
    // =================================================================================

    /**
     * Минимальный условный диаметр, пропускная способность которого не меньше расхода
     * (раздел 3 ТП). Если расход превышает весь ряд — {@link Optional#empty()};
     * вызывающий обязан обработать это как недостижимое подключение, а не молча
     * подставить наибольший диаметр.
     */
    public Optional<DiameterRow> selectForFlow(double flowTph) {
        Map.Entry<Double, DiameterRow> e = byCapacity.ceilingEntry(flowTph);
        return Optional.ofNullable(e).map(Map.Entry::getValue);
    }

    /**
     * Строка справочника по значению ДУ. Если точного значения нет (во входных данных
     * встречаются нетабличные диаметры существующей сети), берётся ближайший больший:
     * занижать пропускную способность существующего участка нельзя.
     */
    public DiameterRow byDnOrNextUp(int dn) {
        DiameterRow exact = byDn.get(dn);
        if (exact != null) {
            return exact;
        }
        for (DiameterRow row : ordered) {
            if (row.getDn() >= dn) {
                return row;
            }
        }
        return largest();
    }

    /** Есть ли такое значение ДУ в справочнике ровно. */
    public boolean hasExactDn(int dn) {
        return byDn.containsKey(dn);
    }

    /** Стоимость 1 м нового строительства для ДУ, руб./м (таблица 1). */
    public double newCostPerM(int dn) {
        return byDnOrNextUp(dn).getNewCostPerM();
    }

    /** Предельная длина непрерывной части сети одного ДУ, м (таблица 1). */
    public double maxRunLength(int dn) {
        return byDnOrNextUp(dn).getMaxRunLength();
    }

    /** Расчётная ширина пары труб, м (таблица 1). */
    public double pairWidth(int dn) {
        return byDnOrNextUp(dn).getPairWidth();
    }

    /** Расчётная высота габарита пары труб, м (таблица 1). */
    public double pairHeight(int dn) {
        return byDnOrNextUp(dn).getPairHeight();
    }

    // =================================================================================
    //  Стоимость камер и врезок
    // =================================================================================

    /**
     * Стоимость тепловой камеры по наибольшему условному диаметру примыкающих участков
     * (раздел 3.2). Одна шкала применяется и к строительству новой камеры,
     * Реконструкции камер в модели нет, шкала применяется только к новым.
     */
    public double chamberCost(int maxAdjacentDn) {
        for (ChamberCostRow row : props.getChamberCostScale()) {
            if (maxAdjacentDn >= row.getDnFrom() && maxAdjacentDn <= row.getDnTo()) {
                return row.getCost();
            }
        }
        // ДУ вне шкалы: ниже первой границы — первая строка, выше последней — последняя.
        ChamberCostRow first = props.getChamberCostScale().get(0);
        ChamberCostRow last = props.getChamberCostScale().get(props.getChamberCostScale().size() - 1);
        return maxAdjacentDn < first.getDnFrom() ? first.getCost() : last.getCost();
    }

    /** Стоимость одной врезки в существующую камеру, руб. (раздел 3.2). */
    public double tieInCost() {
        return props.getTieInCost();
    }

    /** Штраф за неподключенный ОКС с расчётным расходом {@code flowTph}, руб. (раздел 8.3). */
    public double unconnectedPenalty(double flowTph) {
        return props.getUnconnectedPenaltyFixed() + props.getUnconnectedPenaltyPerTph() * flowTph;
    }

    /**
     * Итоговый показатель ранжирования S (раздел 9): чем меньше, тем выше вариант.
     *
     * @param cost   итоговая стоимость варианта, руб.
     * @param length общая протяжённость линейных работ (новые + реконструируемые), м
     */
    public double score(double cost, double length) {
        ReferenceProperties.Scoring s = props.getScoring();
        return s.getCostWeight() * (cost / s.getCostBase())
                + s.getLengthWeight() * (length / s.getLengthBase());
    }

    // =================================================================================
    //  Пространственные ограничения
    // =================================================================================

    /**
     * Правило для типа ограничения из входных данных. Тип нормализуется, затем ищется
     * в таблице 2, затем в псевдонимах; если не найден — возвращается консервативное
     * правило {@code unknownRestriction}, а сам тип попадает в диагностику.
     */
    public RestrictionRow ruleFor(String restrictionType) {
        String key = normalizeType(restrictionType);
        RestrictionRow direct = props.getRestrictions().get(key);
        if (direct != null) {
            return direct;
        }
        String alias = props.getRestrictionAliases().get(key);
        if (alias != null) {
            RestrictionRow viaAlias = props.getRestrictions().get(normalizeType(alias));
            if (viaAlias != null) {
                return viaAlias;
            }
        }
        unknownTypesSeen.add(key);
        return props.getUnknownRestriction();
    }

    /** Канонический тип ограничения после разрешения псевдонима (для отчётности). */
    public String canonicalType(String restrictionType) {
        String key = normalizeType(restrictionType);
        if (props.getRestrictions().containsKey(key)) {
            return key;
        }
        String alias = props.getRestrictionAliases().get(key);
        return alias != null ? normalizeType(alias) : key;
    }

    /** Известен ли тип ограничения справочнику (прямо или через псевдоним). */
    public boolean isKnownType(String restrictionType) {
        String key = normalizeType(restrictionType);
        return props.getRestrictions().containsKey(key)
                || props.getRestrictionAliases().containsKey(key);
    }

    /**
     * Минимальное горизонтальное расстояние до объекта, м. Для существующих ОКС оно
     * зависит от условного диаметра новой сети (таблица 2), для остальных типов
     * задано константой.
     */
    public double minHorizontalDistance(RestrictionRow rule, int newDn) {
        List<DistanceByDn> byDnList = rule.getMinHorizontalByDn();
        if (byDnList != null && !byDnList.isEmpty()) {
            return byDnList.stream()
                    .sorted(Comparator.comparingInt(DistanceByDn::getDnTo))
                    .filter(r -> newDn <= r.getDnTo())
                    .findFirst()
                    .map(DistanceByDn::getDist)
                    .orElseGet(() -> byDnList.stream()
                            .mapToDouble(DistanceByDn::getDist)
                            .max()
                            .orElse(0d));
        }
        return rule.getMinHorizontalDist() != null ? rule.getMinHorizontalDist() : 0d;
    }

    /**
     * Полуширина «запретной» полосы вокруг объекта для трассы условного диаметра
     * {@code newDn}: минимальное горизонтальное расстояние измеряется между внешними
     * границами расчётных габаритов (раздел 5 ТП), поэтому к нему добавляется половина
     * расчётной ширины пары труб.
     */
    public double clearanceBuffer(RestrictionRow rule, int newDn) {
        return minHorizontalDistance(rule, newDn) + pairWidth(newDn) / 2d;
    }

    /** Условные габариты и глубина существующей коммуникации (раздел 4). */
    public Optional<UtilityRow> existingUtility(String type) {
        return Optional.ofNullable(props.getExistingUtilities().get(normalizeType(type)));
    }

    /** Типы ограничений, встреченные во входных данных и отсутствующие в справочнике. */
    public Set<String> unknownTypesSeen() {
        return Collections.unmodifiableSet(unknownTypesSeen);
    }

    public void resetUnknownTypes() {
        unknownTypesSeen.clear();
    }

    // =================================================================================
    //  Глубина (дополнительная задача)
    // =================================================================================

    /**
     * Коэффициент стоимости по глубине (раздел 5 ТП):
     * {@code Kгл = 1 + 0,10 * (h - 3)} при {@code h > 3}, иначе 1.
     *
     * @param depthToTop глубина до верхней границы расчётного габарита, м
     */
    public double depthCostFactor(double depthToTop) {
        ReferenceProperties.Depth d = props.getDepth();
        if (depthToTop <= d.getFreeDepthThreshold()) {
            return 1.0;
        }
        return 1.0 + d.getCostPerExtraMeter() * (depthToTop - d.getFreeDepthThreshold());
    }

    /**
     * Средний коэффициент глубины на участке спуска или подъёма: зависимость Kгл от
     * глубины линейна, поэтому берётся среднее арифметическое коэффициентов концов
     * (раздел 5 ТП).
     */
    public double depthCostFactorAverage(double depthStart, double depthEnd) {
        return (depthCostFactor(depthStart) + depthCostFactor(depthEnd)) / 2d;
    }

    private static String normalizeType(String type) {
        return type == null ? "" : type.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}
