package ru.lct.heatroute.depth;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.model.RestrictionObject;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.domain.reference.ReferenceProperties;
import ru.lct.heatroute.domain.reference.ReferenceProperties.UtilityRow;
import ru.lct.heatroute.domain.reference.VerticalRule;
import ru.lct.heatroute.domain.result.CalculationVariant;
import ru.lct.heatroute.domain.result.NewSegment;
import ru.lct.heatroute.domain.result.TechnicalNodeResult;
import ru.lct.heatroute.geo.Geo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Дополнительный режим: трассировка тепловой сети с учётом глубины.
 * <p>
 * Раздел 5 приложения в редакции от 18.09 объявляет режим необязательным и ранжирует
 * его отдельно от двумерного: результаты двух режимов в одно ранжирование не сводятся.
 * Правила, реализованные здесь:
 * <ul>
 *   <li>обычная глубина 3,0 м до верха расчётного габарита, минимальная 0,7 м;
 *       дискретного шага подбора приложение больше не задаёт, и глубина берётся ровно
 *       та, которая нужна по просвету;</li>
 *   <li>в месте пересечения новая сеть проходит выше или ниже существующей
 *       коммуникации с соблюдением вертикального просвета; если справочник допускает
 *       оба варианта, выбирается тот, что дешевле;</li>
 *   <li>профиль перехода состоит из заглубления, горизонтальной вставки 4 м в зоне
 *       пересечения и возврата к обычной глубине; уклон не круче 0,10 м/м;</li>
 *   <li>Kгл = 1 + 0,10 · (h − 3) при h > 3, на наклонном участке — среднее
 *       коэффициентов концов;</li>
 *   <li>пересечение отметки 3,0 м делит участок, и в этой точке ставится
 *       технический узел.</li>
 * </ul>
 * <p>
 * Профиль строится по уже найденной трассе. Маршрут в плане при этом может измениться:
 * стоимость участков с заглублением пересчитывается, и ранжирование вариантов после
 * этого другое — выигрывает тот, у кого пересечений меньше или они дешевле.
 */
@Slf4j
@Component
public class DepthPlanner {

    /** Насколько близко к точке врезки пересечение считается самим присоединением, м. */
    private static final double TIE_IN_TOLERANCE_M = 1.0;

    private final ReferenceCatalog catalog;

    public DepthPlanner(ReferenceCatalog catalog) {
        this.catalog = catalog;
    }

    /** Результат: вариант с профилем по глубине и перечень пересечений. */
    @Value
    public static class Result {
        CalculationVariant variant;
        List<UtilityCrossing> crossings;
        /** Участки, для которых допустимый профиль в заданном диапазоне глубин не найден. */
        List<String> unresolved;
    }

    /**
     * Объект, под которым новая сеть обязана идти не выше заданной глубины:
     * дорога, трамвайные пути (таблица 2, {@code minTopBelowSurface}).
     */
    @Value
    private static class SurfaceLimit {
        String id;
        Geometry geometry;
        /** Верх габарита новой сети не выше этой глубины, м. */
        double minDepth;
    }

    /** Существующая коммуникация с известной условной глубиной. */
    @Value
    private static class Utility {
        String id;
        String type;
        Geometry geometry;
        double depthToTop;
        double height;
        VerticalRule rule;
        double minClearance;
    }

    public Result apply(CalculationVariant variant, InputScene scene) {
        List<Utility> utilities = collectUtilities(scene);
        List<SurfaceLimit> surfaceLimits = collectSurfaceLimits(scene);
        if (utilities.isEmpty()) {
            log.debug("Коммуникаций с заданной условной глубиной в наборе нет; "
                    + "профиль строится на обычной глубине");
        }

        ReferenceProperties.Depth params = catalog.props().getDepth();
        List<Coordinate> tieInPoints = variant.getTieIns().stream()
                .map(t -> t.getLocation().getCoordinate())
                .collect(Collectors.toList());

        List<NewSegment> segments = new ArrayList<>();
        List<TechnicalNodeResult> nodes = new ArrayList<>(variant.getTechnicalNodes());
        List<UtilityCrossing> crossings = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        int nodeCounter = nodes.size();

        for (List<NewSegment> chain : chains(variant.getSegments())) {
            // Пересечения всей цепочки в единой системе отсчёта: разбег под заглубление
            // почти никогда не помещается внутри одного участка.
            List<Placed> placed = new ArrayList<>();
            double chainLength = 0;
            for (int i = 0; i < chain.size(); i++) {
                NewSegment segment = chain.get(i);
                for (UtilityCrossing c : findCrossings(segment, utilities, surfaceLimits,
                        params, tieInPoints)) {
                    placed.add(new Placed(c, i, c.getStation(), chainLength + c.getStation()));
                }
                chainLength += segment.getLength();
            }
            placed.sort(Comparator.comparingDouble(Placed::getChainStation));

            DepthProfile profile = buildProfile(chainLength, placed, params, unresolved);

            double at = 0;
            for (int i = 0; i < chain.size(); i++) {
                NewSegment segment = chain.get(i);
                List<NewSegment> parts = split(segment,
                        subProfile(profile, at, at + segment.getLength()), params);

                // Технический узел там, где меняется глубина: раздел 3 ТП относит глубину
                // к параметрам участка, а смена параметра делит линию. Узел не только
                // выгружается — он связывает части между собой, иначе разрезанный
                // участок перестаёт быть непрерывной ниткой.
                List<NewSegment> stitched = new ArrayList<>(parts.size());
                String previousNode = segment.getStartNodeId();
                for (int k = 0; k < parts.size(); k++) {
                    NewSegment part = parts.get(k);
                    String endNode = segment.getEndNodeId();
                    if (k + 1 < parts.size()) {
                        endNode = "tnd_" + variant.getVariantId() + "_" + (++nodeCounter);
                        Coordinate node = part.getGeometry().getCoordinates()[
                                part.getGeometry().getNumPoints() - 1];
                        nodes.add(TechnicalNodeResult.builder()
                                .id(endNode)
                                .variantId(variant.getVariantId())
                                .location(Geo.point(node))
                                .reason("смена глубины прокладки")
                                .build());
                    }
                    stitched.add(part.toBuilder()
                            .startNodeId(previousNode)
                            .endNodeId(endNode)
                            .build());
                    previousNode = endNode;
                }
                segments.addAll(stitched);

                for (Placed p : placed) {
                    if (p.getSegmentIndex() == i) {
                        crossings.add(reconcile(p, profile, stitched, segment));
                    }
                }
                at += segment.getLength();
            }
        }

        CalculationVariant updated = variant.toBuilder()
                .segments(segments)
                .technicalNodes(nodes)
                .depthCrossings(crossings)
                .build();

        log.debug("Профиль по глубине: участков {} - {}, пересечений {}, без решения {}",
                variant.getSegments().size(), segments.size(), crossings.size(),
                unresolved.size());
        return new Result(updated, crossings, unresolved);
    }

    // =================================================================================
    //  Непрерывные цепочки участков
    // =================================================================================

    /** Пересечение, привязанное к своему месту в цепочке. */
    @Value
    private static class Placed {
        UtilityCrossing crossing;
        /** Номер участка цепочки, на котором лежит пересечение. */
        int segmentIndex;
        /** Расстояние от начала этого участка, м. */
        double localStation;
        /** Расстояние от начала всей цепочки, м. */
        double chainStation;
    }

    /**
     * Разбивает дерево новой сети на непрерывные цепочки участков.
     * <p>
     * Профиль по глубине нельзя строить в границах одного участка: при обычной глубине
     * 3,0 м, проходе поверх коммуникации на 2,0 м и предельном уклоне 0,10 м/м на одно
     * только заглубление нужно десять метров разбега, а участок делится материализацией
     * по смене диаметра и способа прокладки и бывает короче. Разбег берётся с соседних
     * участков той же нитки.
     * <p>
     * Цепочка обрывается в узле ветвления: там стоит тепловая камера, у которой одна
     * отметка заложения на все примыкания, поэтому к узлу ветвления нитки приходят
     * на обычной глубине.
     */
    private List<List<NewSegment>> chains(List<NewSegment> segments) {
        Map<String, List<NewSegment>> outgoing = new LinkedHashMap<>();
        Map<String, Integer> incoming = new LinkedHashMap<>();
        for (NewSegment s : segments) {
            outgoing.computeIfAbsent(s.getStartNodeId(), k -> new ArrayList<>()).add(s);
            incoming.merge(s.getEndNodeId(), 1, Integer::sum);
        }

        Deque<NewSegment> starts = new ArrayDeque<>();
        for (NewSegment s : segments) {
            if (!isSimple(s.getStartNodeId(), outgoing, incoming)) {
                starts.add(s);
            }
        }

        List<List<NewSegment>> chains = new ArrayList<>();
        Set<String> used = new HashSet<>();
        while (!starts.isEmpty()) {
            NewSegment head = starts.poll();
            if (!used.add(head.getId())) {
                continue;
            }
            List<NewSegment> chain = new ArrayList<>();
            chain.add(head);
            NewSegment current = head;
            while (isSimple(current.getEndNodeId(), outgoing, incoming)) {
                NewSegment next = outgoing.get(current.getEndNodeId()).get(0);
                if (!used.add(next.getId())) {
                    break;
                }
                chain.add(next);
                current = next;
            }
            chains.add(chain);
        }

        // Страховка от замкнутого контура: дерево циклов не содержит, но выпасть
        // из расчёта участок не должен ни при каких данных.
        for (NewSegment s : segments) {
            if (used.add(s.getId())) {
                chains.add(List.of(s));
            }
        }
        return chains;
    }

    /** Узел простого продолжения: ровно один вход и ровно один выход. */
    private boolean isSimple(String nodeId, Map<String, List<NewSegment>> outgoing,
                             Map<String, Integer> incoming) {
        return incoming.getOrDefault(nodeId, 0) == 1
                && outgoing.getOrDefault(nodeId, List.of()).size() == 1;
    }

    /** Часть профиля цепочки, приходящаяся на один участок, со своей нулевой отметкой. */
    private DepthProfile subProfile(DepthProfile chain, double from, double to) {
        List<DepthProfile.Point> points = new ArrayList<>();
        points.add(new DepthProfile.Point(0, chain.depthAt(from)));
        for (DepthProfile.Point point : chain.getPoints()) {
            if (point.getStation() > from + 1e-6 && point.getStation() < to - 1e-6) {
                points.add(new DepthProfile.Point(point.getStation() - from, point.getDepth()));
            }
        }
        points.add(new DepthProfile.Point(Math.max(0, to - from), chain.depthAt(to)));
        return new DepthProfile(dedupePoints(points));
    }

    /**
     * Приводит пересечение к тому, что действительно построено.
     * <p>
     * Выгрузка обязана описывать сеть, а не намерение: после деления участка пересечение
     * лежит уже на другом объекте, а если манёвр не поместился, труба проходит на обычной
     * глубине — и просвет должен быть посчитан по ней, иначе проверяющий увидит величину,
     * которой в сети нет.
     */
    private UtilityCrossing reconcile(Placed placed, DepthProfile profile,
                                      List<NewSegment> parts, NewSegment origin) {
        UtilityCrossing c = placed.getCrossing();
        double actualDepth = round(profile.depthAt(placed.getChainStation()));
        double newHeight = catalog.pairHeight(origin.getDiameter());
        double clearance = c.getPassage() == UtilityCrossing.Passage.ABOVE
                ? c.getUtilityDepthToTop() - (actualDepth + newHeight)
                : actualDepth - (c.getUtilityDepthToTop() + c.getUtilityHeight());

        String partId = parts.get(parts.size() - 1).getId();
        double station = placed.getLocalStation();
        double run = 0;
        for (NewSegment part : parts) {
            if (placed.getLocalStation() <= run + part.getLength() + 1e-6) {
                partId = part.getId();
                station = placed.getLocalStation() - run;
                break;
            }
            run += part.getLength();
        }

        return new UtilityCrossing(round(station), c.getLocation(), partId,
                c.getUtilityId(), c.getUtilityType(), c.getUtilityDepthToTop(),
                c.getUtilityHeight(), c.getRequiredClearance(), c.getPassage(),
                actualDepth, round(clearance));
    }

    /** Лежит ли точка в месте присоединения к существующей сети. */
    private boolean atTieIn(Coordinate point, List<Coordinate> tieInPoints) {
        for (Coordinate tieIn : tieInPoints) {
            if (tieIn != null && tieIn.distance(point) <= TIE_IN_TOLERANCE_M) {
                return true;
            }
        }
        return false;
    }

    // =================================================================================
    //  Существующие коммуникации
    // =================================================================================

    /**
     * Ограничения по глубине от объектов поверхности.
     * <p>
     * Под проезжей частью и трамвайными путями верх габарита новой сети не должен быть
     * выше заданной отметки. Обычная глубина 3,0 м это перекрывает, но проход поверх
     * чужой коммуникации поднимает трассу — и вот там правило начинает работать.
     * <p>
     * На значениях таблицы 2 (1,0 м под дорогой, 1,2 м под путями) ограничение
     * не срабатывает: самый высокий проход поверх коммуникации даёт около 2,0 м.
     * Это защита от другого набора справочных данных, а не действующее ограничение
     * на конкурсном наборе, и проверить её можно только подменой справочника.
     */
    private List<SurfaceLimit> collectSurfaceLimits(InputScene scene) {
        List<SurfaceLimit> out = new ArrayList<>();
        for (RestrictionObject restriction : scene.getRestrictions()) {
            ReferenceProperties.RestrictionRow rule = restriction.getRule();
            if (rule == null || rule.getVerticalRule() != VerticalRule.BELOW_SURFACE
                    || rule.getMinTopBelowSurface() == null) {
                continue;
            }
            out.add(new SurfaceLimit(restriction.getId(), restriction.getGeometry(),
                    rule.getMinTopBelowSurface()));
        }
        return out;
    }

    /**
     * Наименьшая допустимая глубина в точке.
     * <p>
     * Берётся по точке пересечения, а не по всей горизонтальной вставке: вставка
     * шириной 4 м, а дорога или путь всегда шире, поэтому если пересечение попало
     * внутрь полигона, туда же попадает и вставка.
     */
    private double minDepthAt(Coordinate point, List<SurfaceLimit> limits,
                              ReferenceProperties.Depth params) {
        double floor = params.getMinDepth();
        if (limits.isEmpty()) {
            return floor;
        }
        org.locationtech.jts.geom.Point p = Geo.point(point);
        for (SurfaceLimit limit : limits) {
            if (limit.getGeometry().intersects(p)) {
                floor = Math.max(floor, limit.getMinDepth());
            }
        }
        return floor;
    }

    private List<Utility> collectUtilities(InputScene scene) {
        List<Utility> out = new ArrayList<>();
        for (RestrictionObject restriction : scene.getRestrictions()) {
            UtilityRow row = catalog.existingUtility(restriction.getCanonicalType()).orElse(null);
            if (row == null) {
                continue;
            }
            ReferenceProperties.RestrictionRow rule = restriction.getRule();
            if (rule.getVerticalRule() == null) {
                continue;
            }
            // Габарит существующей тепловой сети берётся по её условному диаметру
            // (таблица 1), у прочих коммуникаций он задан в разделе 4 напрямую.
            double height = row.getHeight() != null
                    ? row.getHeight()
                    : catalog.pairHeight(restriction.getDiameter() == null
                    ? catalog.diameters().get(0).getDn() : restriction.getDiameter());

            out.add(new Utility(
                    restriction.getId(),
                    restriction.getCanonicalType(),
                    restriction.getGeometry(),
                    row.getDepthToTop(),
                    height,
                    rule.getVerticalRule(),
                    rule.getMinVerticalClearance() == null ? 0 : rule.getMinVerticalClearance()));
        }
        return out;
    }

    // =================================================================================
    //  Пересечения
    // =================================================================================

    private List<UtilityCrossing> findCrossings(NewSegment segment, List<Utility> utilities,
                                                List<SurfaceLimit> surfaceLimits,
                                                ReferenceProperties.Depth params,
                                                List<Coordinate> tieInPoints) {
        List<UtilityCrossing> out = new ArrayList<>();
        LineString line = segment.getGeometry();
        double newHeight = catalog.pairHeight(segment.getDiameter());

        for (Utility utility : utilities) {
            if (!utility.getGeometry().intersects(line)) {
                continue;
            }
            Geometry intersection = utility.getGeometry().intersection(line);
            for (Coordinate point : intersection.getCoordinates()) {
                // Пересечение в точке врезки — это само присоединение, а не пересечение:
                // новая сеть там и должна касаться существующей.
                if (atTieIn(point, tieInPoints)) {
                    continue;
                }
                double station = stationOf(line, point);

                Choice choice = chooseDepth(utility, newHeight, params,
                        minDepthAt(point, surfaceLimits, params));
                if (choice == null) {
                    continue;
                }
                out.add(new UtilityCrossing(
                        station, point, segment.getId(), utility.getId(), utility.getType(),
                        utility.getDepthToTop(), utility.getHeight(),
                        utility.getMinClearance(), choice.passage, choice.depth,
                        choice.clearance));
            }
        }
        out.sort(Comparator.comparingDouble(UtilityCrossing::getStation));
        return dedupe(out);
    }

    @Value
    private static class Choice {
        UtilityCrossing.Passage passage;
        double depth;
        double clearance;
        double cost;
    }

    /**
     * Выбор прохождения выше или ниже коммуникации.
     * <p>
     * Если справочник допускает оба варианта, берётся тот, у кого коэффициент стоимости
     * по глубине меньше (раздел 4 приложения). Проход сверху почти всегда дешевле:
     * он уменьшает глубину, а стоимость растёт только при заглублении глубже трёх метров.
     */
    private Choice chooseDepth(Utility utility, double newHeight,
                               ReferenceProperties.Depth params, double minDepth) {
        List<Choice> options = new ArrayList<>();

        if (utility.getRule() == VerticalRule.ABOVE_OR_BELOW) {
            // Сверху: низ новой сети выше верха коммуникации на требуемый просвет.
            double above = utility.getDepthToTop() - utility.getMinClearance() - newHeight;
            double aboveSnapped = snapDown(above);
            if (aboveSnapped >= minDepth) {
                double clearance = utility.getDepthToTop() - (aboveSnapped + newHeight);
                options.add(new Choice(UtilityCrossing.Passage.ABOVE, aboveSnapped,
                        clearance, catalog.depthCostFactor(aboveSnapped)));
            }

            // Снизу: верх новой сети ниже низа коммуникации на требуемый просвет.
            double below = utility.getDepthToTop() + utility.getHeight()
                    + utility.getMinClearance();
            double belowSnapped = snapUp(below);
            if (belowSnapped <= params.getMaxDepth()) {
                double clearance = belowSnapped
                        - (utility.getDepthToTop() + utility.getHeight());
                options.add(new Choice(UtilityCrossing.Passage.BELOW, belowSnapped,
                        clearance, catalog.depthCostFactor(belowSnapped)));
            }
        } else if (utility.getRule() == VerticalRule.BELOW_SURFACE) {
            // Проход под объектом: ограничение задаёт минимальную глубину, обычная
            // глубина его и так перекрывает.
            double depth = Math.max(params.getNormalDepth(), minDepth);
            options.add(new Choice(UtilityCrossing.Passage.BELOW, depth,
                    depth, catalog.depthCostFactor(depth)));
        }

        return options.stream().min(Comparator.comparingDouble(Choice::getCost)).orElse(null);
    }

    // =================================================================================
    //  Профиль
    // =================================================================================

    /**
     * Профиль глубины вдоль участка: обычная глубина, заглубление или подъём к каждому
     * пересечению, горизонтальная вставка в зоне пересечения, возврат.
     */
    private DepthProfile buildProfile(double length, List<Placed> crossings,
                                      ReferenceProperties.Depth params,
                                      List<String> unresolved) {
        double normal = params.getNormalDepth();
        if (crossings.isEmpty()) {
            return DepthProfile.flat(length, normal);
        }

        List<DepthProfile.Point> points = new ArrayList<>();
        points.add(new DepthProfile.Point(0, normal));
        double cursor = 0;
        double currentDepth = normal;

        for (Placed placed : crossings) {
            UtilityCrossing crossing = placed.getCrossing();
            double half = params.getCrossingFlatHalf();
            double flatFrom = placed.getChainStation() - half;
            double flatTo = placed.getChainStation() + half;
            double target = crossing.getNewDepth();

            // Уклон не круче заданного: на изменение глубины нужен разбег.
            double ramp = Math.abs(target - currentDepth) / params.getMaxSlope();
            double rampStart = flatFrom - ramp;

            if (rampStart < cursor - 1e-6 || flatTo > length + 1e-6) {
                // Разбега не хватает: участок слишком короткий для такого манёвра.
                // Раздел 4 приложения требует отметить это, а не строить профиль круче
                // допустимого уклона.
                if (!unresolved.contains(crossing.getSegmentId())) {
                    unresolved.add(crossing.getSegmentId());
                }
                continue;
            }

            points.add(new DepthProfile.Point(rampStart, currentDepth));
            points.add(new DepthProfile.Point(Math.max(rampStart, flatFrom), target));
            points.add(new DepthProfile.Point(Math.min(length, flatTo), target));
            cursor = Math.min(length, flatTo);
            currentDepth = target;
        }

        // Возврат к обычной глубине после последнего пересечения.
        if (Math.abs(currentDepth - normal) > 1e-9) {
            double ramp = Math.abs(normal - currentDepth) / params.getMaxSlope();
            double end = Math.min(length, cursor + ramp);
            points.add(new DepthProfile.Point(end, normal));
        }
        if (points.get(points.size() - 1).getStation() < length - 1e-6) {
            points.add(new DepthProfile.Point(length, currentDepthAt(points, normal)));
        }

        points.sort(Comparator.comparingDouble(DepthProfile.Point::getStation));
        return new DepthProfile(dedupePoints(points));
    }

    private double currentDepthAt(List<DepthProfile.Point> points, double fallback) {
        return points.isEmpty() ? fallback : points.get(points.size() - 1).getDepth();
    }

    // =================================================================================
    //  Деление участка по профилю
    // =================================================================================

    /**
     * Делит участок на части в точках смены уклона и на отметке обычной глубины.
     * Для каждой части считается коэффициент по глубине: на горизонтальной части —
     * по её глубине, на наклонной — среднее коэффициентов концов (раздел 5 ТП).
     */
    private List<NewSegment> split(NewSegment segment, DepthProfile profile,
                                   ReferenceProperties.Depth params) {
        double length = segment.getLength();
        List<Double> stations = new ArrayList<>();
        stations.add(0d);
        stations.addAll(profile.breakStations());
        for (DepthProfile.Point point : profile.getPoints()) {
            stations.add(point.getStation());
        }
        // Пересечение отметки, с которой начинает расти стоимость, тоже делит участок.
        stations.addAll(crossingsOfThreshold(profile, params.getFreeDepthThreshold()));
        stations.add(length);

        List<Double> cuts = new ArrayList<>(new java.util.TreeSet<>(stations));
        List<NewSegment> parts = new ArrayList<>();
        int index = 0;

        for (int i = 0; i + 1 < cuts.size(); i++) {
            double from = cuts.get(i);
            double to = cuts.get(i + 1);
            if (to - from < 0.05) {
                continue;
            }
            double depthFrom = profile.depthAt(from);
            double depthTo = profile.depthAt(to);
            double kDepth = Math.abs(depthFrom - depthTo) < 1e-9
                    ? catalog.depthCostFactor(depthFrom)
                    : catalog.depthCostFactorAverage(depthFrom, depthTo);

            LineString part = substring(segment.getGeometry(), from, to, profile);
            double partLength = part.getLength();
            double cost = partLength * catalog.newCostPerM(segment.getDiameter())
                    * segment.getKSpecial() * kDepth;

            parts.add(segment.toBuilder()
                    .id(cuts.size() > 2 ? segment.getId() + "." + (++index) : segment.getId())
                    .geometry(part)
                    .length(Geo.roundCm(partLength))
                    .depthStart(round(depthFrom))
                    .depthEnd(round(depthTo))
                    .kDepth(round3(kDepth))
                    .cost(Math.round(cost))
                    .build());
        }
        return parts.isEmpty() ? List.of(segment) : parts;
    }

    /** Станции, где профиль пересекает заданную отметку глубины. */
    private List<Double> crossingsOfThreshold(DepthProfile profile, double threshold) {
        List<Double> out = new ArrayList<>();
        List<DepthProfile.Point> points = profile.getPoints();
        for (int i = 0; i + 1 < points.size(); i++) {
            double d1 = points.get(i).getDepth();
            double d2 = points.get(i + 1).getDepth();
            if ((d1 - threshold) * (d2 - threshold) >= 0 || Math.abs(d2 - d1) < 1e-9) {
                continue;
            }
            double t = (threshold - d1) / (d2 - d1);
            out.add(points.get(i).getStation()
                    + t * (points.get(i + 1).getStation() - points.get(i).getStation()));
        }
        return out;
    }

    /** Часть линии между двумя станциями с проставленными Z-координатами. */
    private LineString substring(LineString line, double from, double to, DepthProfile profile) {
        double total = line.getLength();
        org.locationtech.jts.linearref.LengthIndexedLine indexed =
                new org.locationtech.jts.linearref.LengthIndexedLine(line);
        Geometry sub = indexed.extractLine(
                Math.max(0, Math.min(total, from)), Math.max(0, Math.min(total, to)));
        LineString part = sub instanceof LineString ? (LineString) sub : line;

        // Z считается от условной поверхности земли: чем глубже, тем меньше Z.
        Coordinate[] coords = part.getCoordinates();
        double running = from;
        for (int i = 0; i < coords.length; i++) {
            if (i > 0) {
                running += coords[i - 1].distance(coords[i]);
            }
            coords[i] = new Coordinate(coords[i].x, coords[i].y, -round(profile.depthAt(running)));
        }
        return Geo.line(List.of(coords));
    }

    // =================================================================================

    private static double stationOf(LineString line, Coordinate point) {
        org.locationtech.jts.linearref.LengthIndexedLine indexed =
                new org.locationtech.jts.linearref.LengthIndexedLine(line);
        return indexed.project(point);
    }

    /**
     * Округление вниз до сантиметра.
     * <p>
     * Первая редакция приложения требовала подбирать глубину шагом 0,5 м, и глубина
     * округлялась до сетки. В редакции от 18.09 дискретный шаг не задан, а {@code Kгл}
     * линеен, поэтому сетка стала только дорожать решение: округление вверх при проходе
     * снизу забирало до половины метра лишнего заглубления. Теперь берётся ровно та
     * глубина, которая нужна по просвету, а до сантиметра значение доводится лишь
     * затем, чтобы в выгрузке не появлялось шума вроде 2,3999999997.
     */
    private double snapDown(double depth) {
        return Math.floor(depth * 100d) / 100d;
    }

    /** Округление вверх до сантиметра — та же причина, что и у округления вниз. */
    private double snapUp(double depth) {
        return Math.ceil(depth * 100d) / 100d;
    }

    /** Пересечения ближе метра считаются одним: отдельный манёвр между ними не вместить. */
    private List<UtilityCrossing> dedupe(List<UtilityCrossing> crossings) {
        List<UtilityCrossing> out = new ArrayList<>();
        for (UtilityCrossing crossing : crossings) {
            if (out.isEmpty()
                    || crossing.getStation() - out.get(out.size() - 1).getStation() > 1.0) {
                out.add(crossing);
            }
        }
        return out;
    }

    private List<DepthProfile.Point> dedupePoints(List<DepthProfile.Point> points) {
        List<DepthProfile.Point> out = new ArrayList<>();
        for (DepthProfile.Point point : points) {
            if (out.isEmpty()
                    || Math.abs(out.get(out.size() - 1).getStation() - point.getStation()) > 1e-6
                    || Math.abs(out.get(out.size() - 1).getDepth() - point.getDepth()) > 1e-9) {
                out.add(point);
            }
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100d) / 100d;
    }

    private static double round3(double v) {
        return Math.round(v * 1000d) / 1000d;
    }

    /** Сводка по пересечениям для отчёта и интерфейса. */
    public Map<String, Long> summarize(List<UtilityCrossing> crossings) {
        Map<String, Long> out = new LinkedHashMap<>();
        crossings.forEach(c -> out.merge(
                c.getUtilityType() + " " + c.passageLabel(), 1L, Long::sum));
        return out;
    }
}
