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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Дополнительная задача: трассировка тепловой сети с учётом глубины.
 * <p>
 * Правила приложения к кейсу, реализованные здесь:
 * <ul>
 *   <li>обычная глубина 3,0 м до верха расчётного габарита, минимальная 0,7 м,
 *       подбор с шагом 0,5 м;</li>
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
        if (utilities.isEmpty()) {
            log.debug("Коммуникаций с заданной условной глубиной в наборе нет; "
                    + "профиль строится на обычной глубине");
        }

        ReferenceProperties.Depth params = catalog.props().getDepth();
        List<NewSegment> segments = new ArrayList<>();
        List<TechnicalNodeResult> nodes = new ArrayList<>(variant.getTechnicalNodes());
        List<UtilityCrossing> crossings = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        int nodeCounter = nodes.size();

        for (NewSegment segment : variant.getSegments()) {
            List<UtilityCrossing> onSegment = findCrossings(segment, utilities, params);
            crossings.addAll(onSegment);

            DepthProfile profile = buildProfile(segment, onSegment, params, unresolved);
            List<NewSegment> parts = split(segment, profile, params);
            segments.addAll(parts);

            // Технический узел там, где меняется глубина: раздел 3 ТП относит глубину
            // к параметрам участка, а смена параметра делит линию.
            for (int i = 0; i + 1 < parts.size(); i++) {
                NewSegment part = parts.get(i);
                Coordinate at = part.getGeometry().getCoordinates()[
                        part.getGeometry().getNumPoints() - 1];
                nodes.add(TechnicalNodeResult.builder()
                        .id("tnd_" + variant.getVariantId() + "_" + (++nodeCounter))
                        .variantId(variant.getVariantId())
                        .location(Geo.point(at))
                        .reason("смена глубины прокладки")
                        .build());
            }
        }

        CalculationVariant updated = variant.toBuilder()
                .segments(segments)
                .technicalNodes(nodes)
                .depthCrossings(crossings)
                .build();

        log.debug("Профиль по глубине: участков {} → {}, пересечений {}, без решения {}",
                variant.getSegments().size(), segments.size(), crossings.size(),
                unresolved.size());
        return new Result(updated, crossings, unresolved);
    }

    // =================================================================================
    //  Существующие коммуникации
    // =================================================================================

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
            // (таблица 4.2), у прочих коммуникаций он задан в таблице 4.3 напрямую.
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
                                                ReferenceProperties.Depth params) {
        List<UtilityCrossing> out = new ArrayList<>();
        LineString line = segment.getGeometry();
        double newHeight = catalog.pairHeight(segment.getDiameter());

        for (Utility utility : utilities) {
            if (!utility.getGeometry().intersects(line)) {
                continue;
            }
            Geometry intersection = utility.getGeometry().intersection(line);
            for (Coordinate point : intersection.getCoordinates()) {
                double station = stationOf(line, point);

                Choice choice = chooseDepth(utility, newHeight, params);
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
                               ReferenceProperties.Depth params) {
        List<Choice> options = new ArrayList<>();

        if (utility.getRule() == VerticalRule.ABOVE_OR_BELOW) {
            // Сверху: низ новой сети выше верха коммуникации на требуемый просвет.
            double above = utility.getDepthToTop() - utility.getMinClearance() - newHeight;
            double aboveSnapped = snapDown(above, params);
            if (aboveSnapped >= params.getMinDepth()) {
                double clearance = utility.getDepthToTop() - (aboveSnapped + newHeight);
                options.add(new Choice(UtilityCrossing.Passage.ABOVE, aboveSnapped,
                        clearance, catalog.depthCostFactor(aboveSnapped)));
            }

            // Снизу: верх новой сети ниже низа коммуникации на требуемый просвет.
            double below = utility.getDepthToTop() + utility.getHeight()
                    + utility.getMinClearance();
            double belowSnapped = snapUp(below, params);
            if (belowSnapped <= params.getMaxDepth()) {
                double clearance = belowSnapped
                        - (utility.getDepthToTop() + utility.getHeight());
                options.add(new Choice(UtilityCrossing.Passage.BELOW, belowSnapped,
                        clearance, catalog.depthCostFactor(belowSnapped)));
            }
        } else if (utility.getRule() == VerticalRule.BELOW_SURFACE) {
            // Проход под объектом: ограничение задаёт минимальную глубину, обычная
            // глубина его и так перекрывает.
            double depth = Math.max(params.getNormalDepth(), params.getMinDepth());
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
    private DepthProfile buildProfile(NewSegment segment, List<UtilityCrossing> crossings,
                                      ReferenceProperties.Depth params,
                                      List<String> unresolved) {
        double length = segment.getLength();
        double normal = params.getNormalDepth();
        if (crossings.isEmpty()) {
            return DepthProfile.flat(length, normal);
        }

        List<DepthProfile.Point> points = new ArrayList<>();
        points.add(new DepthProfile.Point(0, normal));
        double cursor = 0;
        double currentDepth = normal;

        for (UtilityCrossing crossing : crossings) {
            double half = params.getCrossingFlatHalf();
            double flatFrom = crossing.getStation() - half;
            double flatTo = crossing.getStation() + half;
            double target = crossing.getNewDepth();

            // Уклон не круче заданного: на изменение глубины нужен разбег.
            double ramp = Math.abs(target - currentDepth) / params.getMaxSlope();
            double rampStart = flatFrom - ramp;

            if (rampStart < cursor - 1e-6 || flatTo > length + 1e-6) {
                // Разбега не хватает: участок слишком короткий для такого манёвра.
                // Раздел 4 приложения требует отметить это, а не строить профиль круче
                // допустимого уклона.
                if (!unresolved.contains(segment.getId())) {
                    unresolved.add(segment.getId());
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
     * по её глубине, на наклонной — среднее коэффициентов концов (раздел 6.1 ТП).
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

    /** Округление вниз до шага подбора глубины. */
    private double snapDown(double depth, ReferenceProperties.Depth params) {
        return Math.floor(depth / params.getStep()) * params.getStep();
    }

    /** Округление вверх до шага подбора глубины. */
    private double snapUp(double depth, ReferenceProperties.Depth params) {
        return Math.ceil(depth / params.getStep()) * params.getStep();
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
