package ru.lct.heatroute.compliance;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.model.ExistingChamber;
import ru.lct.heatroute.domain.model.ExistingSegment;
import ru.lct.heatroute.domain.model.FutureOks;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.domain.reference.ReferenceProperties.DiameterRow;
import ru.lct.heatroute.ingest.RawFeature;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Проверка готовой выгрузки на соответствие техническому приложению.
 * <p>
 * Смысл этого класса не в том, чтобы поймать свои же ошибки — их ловят тесты. Смысл
 * в том, что проверить можно **любую** выгрузку: свою, чужую, вчерашнюю, полученную
 * из другого сервиса. Департаменту, который принимает такие расчёты от подрядчиков,
 * нужна именно такая ручка: загрузил входной набор и результат, получил перечень
 * нарушений с указанием объектов.
 * <p>
 * Проверка идёт по выгрузке, а не по внутренним структурам расчёта, и ничего о них
 * не знает. Единственное, что берётся из сервиса, — справочник приложения: таблица
 * диаметров, шкала камер, коэффициенты и формулы. Он вынесен в конфигурацию и
 * подменяется снаружи, поэтому проверка работает и с другим справочником.
 */
@Slf4j
@Component
public class ComplianceChecker {

    /** Допуск на сравнение длин, м. Округление в выгрузке идёт до сантиметра. */
    private static final double LENGTH_TOLERANCE_M = 0.5;

    /** Допуск на сравнение денег, руб. Стоимость в выгрузке округлена до рубля. */
    private static final double MONEY_TOLERANCE = 3.0;

    /** Допуск на сравнение показателя: в выгрузке он округлён до третьего знака. */
    private static final double SCORE_TOLERANCE = 0.002;

    /** Предел угла поворота из раздела 2.1, град. */
    private static final double MAX_TURN_DEG = 90.0;

    private static final int MAX_CHAMBER_DEGREE = 4;

    /** Насколько близко к существующей линии должна лежать камера, чтобы считаться на ней, м. */
    private static final double ON_LINE_TOLERANCE_M = 1.0;

    private static final Map<String, Set<String>> REQUIRED_ATTRIBUTES = Map.of(
            "heat_network", Set.of("id", "object_type", "variant_id", "start_node_id",
                    "end_node_id", "flow_tph", "diameter", "length", "laying_method",
                    "depth_start", "depth_end", "cost"),
            "heat_chamber", Set.of("id", "object_type", "variant_id", "diameter", "cost"),
            "technical_node", Set.of("id", "object_type", "variant_id"),
            "variant_summary", Set.of("id", "object_type", "variant_id", "rank",
                    "construction_cost", "chamber_construction_cost",
                    "existing_chamber_tie_in_count", "existing_chamber_tie_in_cost",
                    "unconnected_penalty", "calculated_cost", "new_network_length",
                    "score", "unconnected_oks_ids"));

    private final ReferenceCatalog catalog;

    public ComplianceChecker(ReferenceCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * @param features объекты выгрузки как они прочитаны из файла: атрибуты как есть,
     *                 геометрия уже в рабочей проекции
     * @param scene    входной набор, по которому считался результат. Без него часть
     *                 правил проверить нельзя, и они будут пропущены с пометкой
     */
    public ComplianceReport check(List<RawFeature> features, InputScene scene) {
        Session session = new Session();

        Map<String, Variant> variants = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();

        for (RawFeature feature : features) {
            String type = feature.str("object_type");
            counts.merge(type == null ? "<без типа>" : type, 1L, Long::sum);

            Set<String> required = REQUIRED_ATTRIBUTES.get(type);
            session.check(ComplianceRule.OBJECT_TYPE_ALLOWED, null,
                    List.of(String.valueOf(feature.str("id"))), required != null,
                    () -> "тип " + type + " приложением не предусмотрен");
            if (required == null) {
                continue;
            }

            String id = feature.str("id");
            List<String> missing = new ArrayList<>();
            for (String attribute : required) {
                if (!feature.properties().containsKey(attribute)) {
                    missing.add(attribute);
                }
            }
            missing.sort(String::compareTo);
            session.check(ComplianceRule.REQUIRED_ATTRIBUTES, feature.str("variant_id"),
                    List.of(String.valueOf(id)), missing.isEmpty(),
                    () -> "не хватает атрибутов: " + String.join(", ", missing));

            checkGeometryKind(session, feature, type, id);

            // Объект без variant_id — это нарушение состава, оно уже отмечено выше.
            // Но в перечень вариантов такое значение попадать не должно: «вариант null»
            // в отчёте выглядит как ошибка отчёта, а не как ошибка выгрузки.
            String variantId = feature.str("variant_id");
            variants.computeIfAbsent(variantId == null ? "без variant_id" : variantId,
                    Variant::new).add(type, feature);
        }

        for (Variant variant : variants.values()) {
            checkVariant(session, variant, scene);
        }
        checkRanks(session, variants.values());

        List<String> skipped = new ArrayList<>();
        if (scene == null) {
            skipped.add("Без входного набора не проверены: ссылки концов участков на узлы, "
                    + "диаметр камеры присоединения (его задаёт разделённая ею существующая "
                    + "линия), связность с местом присоединения, монотонность диаметра, "
                    + "предельная длина по путям, штраф и список неподключённых точек");
        }

        return new ComplianceReport(session.checks, session.findings, counts,
                new ArrayList<>(variants.keySet()), skipped);
    }

    // =================================================================================
    //  Объект по отдельности
    // =================================================================================

    private void checkGeometryKind(Session session, RawFeature feature, String type, String id) {
        Geometry geometry = feature.geometry();
        if ("variant_summary".equals(type)) {
            session.check(ComplianceRule.GEOMETRY_KIND, feature.str("variant_id"),
                    List.of(String.valueOf(id)), geometry == null,
                    () -> "сводка по варианту должна передаваться без геометрии");
            return;
        }
        boolean expectLine = "heat_network".equals(type);
        boolean matches = expectLine ? geometry instanceof LineString : geometry instanceof Point;
        session.check(ComplianceRule.GEOMETRY_KIND, feature.str("variant_id"),
                List.of(String.valueOf(id)), matches,
                () -> "ожидалась " + (expectLine ? "линия" : "точка") + ", получено "
                        + (geometry == null ? "пусто" : geometry.getGeometryType()));

        if (geometry != null) {
            boolean flat = true;
            for (Coordinate c : geometry.getCoordinates()) {
                if (!Double.isNaN(c.getZ())) {
                    flat = false;
                    break;
                }
            }
            boolean isFlat = flat;
            session.check(ComplianceRule.NO_Z_COORDINATE, feature.str("variant_id"),
                    List.of(String.valueOf(id)), isFlat,
                    () -> "в координатах есть третье число");
        }
    }

    // =================================================================================
    //  Вариант целиком
    // =================================================================================

    private void checkVariant(Session session, Variant variant, InputScene scene) {
        String vid = variant.id;

        session.check(ComplianceRule.ONE_SUMMARY_PER_VARIANT, vid, List.of(),
                variant.summaries.size() == 1,
                () -> "сводных записей " + variant.summaries.size() + ", должна быть одна");
        if (variant.summaries.size() != 1 || variant.segments.isEmpty()) {
            return;
        }
        RawFeature summary = variant.summaries.get(0);

        Set<String> knownNodes = new LinkedHashSet<>();
        variant.chambers.forEach(c -> knownNodes.add(c.str("id")));
        variant.nodes.forEach(n -> knownNodes.add(n.str("id")));
        if (scene != null) {
            scene.getChambers().forEach(c -> knownNodes.add(c.getId()));
            scene.getFutureOks().forEach(o -> knownNodes.add(o.getConnectionPointId()));
            scene.getFutureOks().forEach(o -> knownNodes.add(o.getId()));
        }

        double segmentCost = 0;
        double totalLength = 0;
        Map<String, List<RawFeature>> adjacency = new LinkedHashMap<>();

        for (RawFeature segment : variant.segments) {
            String id = segment.str("id");
            LineString line = segment.geometry() instanceof LineString
                    ? (LineString) segment.geometry() : null;
            String start = segment.str("start_node_id");
            String end = segment.str("end_node_id");
            adjacency.computeIfAbsent(start, k -> new ArrayList<>()).add(segment);
            adjacency.computeIfAbsent(end, k -> new ArrayList<>()).add(segment);

            if (scene != null) {
                session.check(ComplianceRule.NODE_REFERENCES, vid, List.of(id),
                        knownNodes.contains(start) && knownNodes.contains(end),
                        () -> "узлы " + start + " и " + end + " должны быть точкой подключения, "
                                + "камерой или техническим узлом");
            }

            double declaredLength = segment.num("length").orElse(0d);
            totalLength += declaredLength;
            segmentCost += segment.num("cost").orElse(0d);

            if (line != null) {
                double measured = line.getLength();
                session.check(ComplianceRule.LENGTH_MATCHES_GEOMETRY, vid, List.of(id),
                        Math.abs(measured - declaredLength)
                                <= Math.max(LENGTH_TOLERANCE_M, measured * 0.002),
                        () -> String.format("в атрибуте %.2f м, по геометрии %.2f м",
                                declaredLength, measured));
                checkTurns(session, vid, id, line);
            }

            String laying = segment.str("laying_method");
            session.check(ComplianceRule.LAYING_METHOD, vid, List.of(id),
                    "base".equals(laying) || "special".equals(laying),
                    () -> "способ прокладки " + laying + " не из набора base и special");

            int dn = segment.num("diameter").orElse(0d).intValue();
            double flow = segment.num("flow_tph").orElse(0d);
            DiameterRow row = catalog.hasExactDn(dn) ? catalog.byDnOrNextUp(dn) : null;
            session.check(ComplianceRule.DIAMETER_CAPACITY, vid, List.of(id),
                    row != null && row.getCapacityTph() >= flow - 1e-9,
                    () -> row == null
                            ? "ДУ " + dn + " нет в таблице 1"
                            : String.format("расход %.2f т/ч выше пропускной способности "
                                    + "ДУ %d (%.1f т/ч)", flow, dn, row.getCapacityTph()));

            if (row != null && line != null) {
                double expected = line.getLength() * catalog.newCostPerM(dn);
                double actual = segment.num("cost").orElse(0d);
                if ("base".equals(laying)) {
                    session.check(ComplianceRule.SEGMENT_COST, vid, List.of(id),
                            Math.abs(actual - expected) <= Math.max(2.0, expected * 0.002),
                            () -> String.format("стоимость %.0f, а длина на цену метра "
                                    + "даёт %.0f", actual, expected));
                } else {
                    session.check(ComplianceRule.SEGMENT_COST, vid, List.of(id),
                            actual > expected,
                            () -> String.format("специальный проход должен стоить дороже "
                                    + "обычного: %.0f против %.0f", actual, expected));
                }
            }
        }

        checkChambers(session, variant, scene, adjacency);
        Map<String, RawFeature> parentOf = checkPaths(session, variant, scene, adjacency);
        checkSelfIntersections(session, variant);
        checkSummary(session, variant, summary, scene, segmentCost, totalLength, parentOf);
    }

    private void checkTurns(Session session, String vid, String id, LineString line) {
        Coordinate[] cs = line.getCoordinates();
        for (int i = 1; i + 1 < cs.length; i++) {
            double turn = turnDeg(cs[i - 1], cs[i], cs[i + 1]);
            int vertex = i;
            session.check(ComplianceRule.TURN_ANGLE, vid, List.of(id),
                    turn <= MAX_TURN_DEG + 1e-6,
                    () -> String.format("поворот %.1f° в вершине %d", turn, vertex));
        }
    }

    private void checkChambers(Session session, Variant variant, InputScene scene,
                               Map<String, List<RawFeature>> adjacency) {
        for (RawFeature chamber : variant.chambers) {
            String id = chamber.str("id");
            List<RawFeature> attached = adjacency.getOrDefault(id, List.of());

            int hostDn = 0;
            int hostCount = 0;
            if (scene != null && chamber.geometry() != null) {
                Point location = chamber.geometry().getFactory()
                        .createPoint(chamber.geometry().getCoordinate());
                for (ExistingSegment existing : scene.getSegments()) {
                    if (existing.getGeometry() == null
                            || existing.getGeometry().distance(location) > ON_LINE_TOLERANCE_M) {
                        continue;
                    }
                    // Учитываются все существующие линии под камерой, а не первая найденная:
                    // диаметр камеры задаёт самая толстая из них, а примыкание занимает
                    // каждая. Остановка на первой занижала бы и то, и другое.
                    hostDn = Math.max(hostDn, existing.getDiameter());
                    hostCount += splitsLine(existing.getGeometry(), location) ? 2 : 1;
                }
            }

            int degree = attached.size() + hostCount;
            session.check(ComplianceRule.CHAMBER_DEGREE, variant.id, List.of(id),
                    degree <= MAX_CHAMBER_DEGREE,
                    () -> "примыкает " + degree + " участков при пределе " + MAX_CHAMBER_DEGREE);

            int maxAdjacent = hostDn;
            for (RawFeature segment : attached) {
                maxAdjacent = Math.max(maxAdjacent, segment.num("diameter").orElse(0d).intValue());
            }
            int declared = chamber.num("diameter").orElse(0d).intValue();
            int expectedDn = maxAdjacent;
            // Без входного набора существующую линию под камерой не увидеть, а именно она
            // часто и задаёт диаметр камеры присоединения. Сравнивать в этом случае нельзя:
            // правило обвиняло бы верную выгрузку. Пропуск отмечается в отчёте.
            if (scene != null) {
                session.check(ComplianceRule.CHAMBER_DIAMETER, variant.id, List.of(id),
                        declared == expectedDn,
                        () -> "ДУ " + declared + ", а наибольший ДУ примыкающих участков "
                                + expectedDn);
            }

            double expectedCost = catalog.chamberCost(declared);
            double actualCost = chamber.num("cost").orElse(0d);
            session.check(ComplianceRule.CHAMBER_COST, variant.id, List.of(id),
                    Math.abs(actualCost - expectedCost) < 1,
                    () -> String.format("стоимость %.0f, по шкале для ДУ %d ожидается %.0f",
                            actualCost, declared, expectedCost));
        }
    }

    /**
     * Делит ли камера существующую линию на две части. Линия, проходящая через камеру,
     * занимает два примыкания из четырёх (раздел 2.1, разъяснение №12), а линия, которая
     * в камере заканчивается, — одно, и тогда новых участков к камере подходит на один
     * больше.
     */
    private boolean splitsLine(Geometry line, Point at) {
        Coordinate[] cs = line.getCoordinates();
        if (cs.length == 0) {
            return false;
        }
        Coordinate location = at.getCoordinate();
        return location.distance(cs[0]) > ON_LINE_TOLERANCE_M
                && location.distance(cs[cs.length - 1]) > ON_LINE_TOLERANCE_M;
    }

    /**
     * Обход от мест присоединения к точкам подключения. Возвращает дерево родителей,
     * по которому дальше читаются пути.
     * <p>
     * Ходить надо строго от места присоединения: если идти от точки подключения
     * в любую непосещённую сторону, на развилке обход уйдёт в соседнюю ветвь, и падение
     * диаметра вниз по ветви будет выглядеть нарушением, которого нет.
     */
    private Map<String, RawFeature> checkPaths(Session session, Variant variant,
                                               InputScene scene,
                                               Map<String, List<RawFeature>> adjacency) {
        Map<String, RawFeature> parentOf = new LinkedHashMap<>();
        if (scene == null) {
            return parentOf;
        }

        Set<String> roots = new LinkedHashSet<>();
        Set<String> existingChamberIds = new LinkedHashSet<>();
        scene.getChambers().stream().map(ExistingChamber::getId).forEach(existingChamberIds::add);

        for (String nodeId : adjacency.keySet()) {
            if (existingChamberIds.contains(nodeId)) {
                roots.add(nodeId);
                continue;
            }
            RawFeature chamber = variant.chamberById.get(nodeId);
            if (chamber == null || chamber.geometry() == null) {
                continue;
            }
            Point location = chamber.geometry().getFactory()
                    .createPoint(chamber.geometry().getCoordinate());
            for (ExistingSegment existing : scene.getSegments()) {
                if (existing.getGeometry() != null
                        && existing.getGeometry().distance(location) <= ON_LINE_TOLERANCE_M) {
                    roots.add(nodeId);
                    break;
                }
            }
        }

        session.check(ComplianceRule.NETWORK_CONNECTED, variant.id, List.of(), !roots.isEmpty(),
                () -> "в варианте не опознано ни одного места присоединения "
                        + "к существующей сети");
        if (roots.isEmpty()) {
            return parentOf;
        }

        Set<String> visited = new LinkedHashSet<>(roots);
        Deque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            for (RawFeature segment : adjacency.getOrDefault(node, List.of())) {
                String other = node.equals(segment.str("start_node_id"))
                        ? segment.str("end_node_id") : segment.str("start_node_id");
                if (!visited.add(other)) {
                    continue;
                }
                parentOf.put(other, segment);
                queue.add(other);
            }
        }

        List<String> orphans = new ArrayList<>();
        for (String node : adjacency.keySet()) {
            if (!visited.contains(node)) {
                orphans.add(node);
            }
        }
        session.check(ComplianceRule.NETWORK_CONNECTED, variant.id, orphans, orphans.isEmpty(),
                () -> "узлы не связаны ни с одним местом присоединения: "
                        + String.join(", ", orphans));

        for (FutureOks oks : scene.getFutureOks()) {
            String pointId = oks.getConnectionPointId();
            RawFeature step = parentOf.get(pointId);
            if (step == null) {
                step = parentOf.get(oks.getId());
            }
            if (step == null) {
                continue;               // точка не подключена, это проверяет другое правило
            }
            List<RawFeature> path = pathToRoot(parentOf, pointId, oks.getId(),
                    variant.segments.size() + 5);
            if (path.size() < 2) {
                continue;
            }
            checkMonotonicity(session, variant.id, pointId, path);
            checkRunLength(session, variant.id, pointId, path);
        }
        return parentOf;
    }

    private List<RawFeature> pathToRoot(Map<String, RawFeature> parentOf, String pointId,
                                        String fallbackId, int guard) {
        List<RawFeature> path = new ArrayList<>();
        String current = parentOf.containsKey(pointId) ? pointId : fallbackId;
        int steps = 0;
        while (parentOf.containsKey(current) && steps++ < guard) {
            RawFeature segment = parentOf.get(current);
            path.add(segment);
            String next = current.equals(segment.str("start_node_id"))
                    ? segment.str("end_node_id") : segment.str("start_node_id");
            if (Objects.equals(next, current)) {
                break;
            }
            current = next;
        }
        return path;
    }

    private void checkMonotonicity(Session session, String vid, String pointId,
                                   List<RawFeature> path) {
        int previous = 0;
        for (RawFeature segment : path) {
            int dn = segment.num("diameter").orElse(0d).intValue();
            int before = previous;
            if (previous > 0) {
                session.check(ComplianceRule.DIAMETER_MONOTONIC, vid,
                        List.of(segment.str("id")), dn >= previous,
                        () -> "на пути от точки " + pointId + " диаметр падает с " + before
                                + " до " + dn);
            }
            previous = dn;
        }
    }

    private void checkRunLength(Session session, String vid, String pointId,
                               List<RawFeature> path) {
        int runDn = 0;
        double runLength = 0;
        List<String> runIds = new ArrayList<>();
        for (int i = 0; i <= path.size(); i++) {
            int dn = i < path.size() ? path.get(i).num("diameter").orElse(0d).intValue() : -1;
            if (dn != runDn) {
                if (runDn > 0) {
                    double limit = catalog.maxRunLength(runDn);
                    double accumulated = runLength;
                    int checkedDn = runDn;
                    session.check(ComplianceRule.RUN_LENGTH_LIMIT, vid, List.copyOf(runIds),
                            runLength <= limit + 1e-6,
                            () -> String.format("на пути от точки %s подряд %.1f м на ДУ %d "
                                            + "при пределе %.0f м",
                                    pointId, accumulated, checkedDn, limit));
                }
                runDn = dn;
                runLength = 0;
                runIds.clear();
            }
            if (i < path.size()) {
                runLength += path.get(i).num("length").orElse(0d);
                runIds.add(path.get(i).str("id"));
            }
        }
    }

    /**
     * Пересечения новых участков вне общего узла. Общий узел допустим: раздел 2.1
     * запрещает пересечения именно «вне общего узла», а сходящиеся в камере участки
     * геометрически касаются в ней по построению.
     */
    private void checkSelfIntersections(Session session, Variant variant) {
        List<RawFeature> segments = variant.segments;
        for (int i = 0; i < segments.size(); i++) {
            for (int j = i + 1; j < segments.size(); j++) {
                RawFeature a = segments.get(i);
                RawFeature b = segments.get(j);
                if (shareNode(a, b) || a.geometry() == null || b.geometry() == null) {
                    continue;
                }
                boolean crosses = a.geometry().relate(b.geometry(), "T********");
                session.check(ComplianceRule.NO_SELF_INTERSECTION, variant.id,
                        List.of(a.str("id"), b.str("id")), !crosses,
                        () -> "участки пересекаются, не имея общего узла");
            }
        }
    }

    private static boolean shareNode(RawFeature a, RawFeature b) {
        Set<String> nodes = new LinkedHashSet<>();
        nodes.add(a.str("start_node_id"));
        nodes.add(a.str("end_node_id"));
        return nodes.contains(b.str("start_node_id")) || nodes.contains(b.str("end_node_id"));
    }

    private void checkSummary(Session session, Variant variant, RawFeature summary,
                              InputScene scene, double segmentCost, double totalLength,
                              Map<String, RawFeature> parentOf) {
        String vid = variant.id;
        double chamberCost = variant.chambers.stream()
                .mapToDouble(c -> c.num("cost").orElse(0d)).sum();
        double tieInCost = summary.num("existing_chamber_tie_in_cost").orElse(0d);
        int tieInCount = summary.num("existing_chamber_tie_in_count").orElse(0d).intValue();
        double construction = summary.num("construction_cost").orElse(0d);
        double penalty = summary.num("unconnected_penalty").orElse(0d);
        double total = summary.num("calculated_cost").orElse(0d);
        double declaredLength = summary.num("new_network_length").orElse(0d);

        session.check(ComplianceRule.SUMMARY_SUMS, vid, List.of(summary.str("id")),
                Math.abs(summary.num("chamber_construction_cost").orElse(0d) - chamberCost)
                        < MONEY_TOLERANCE,
                () -> String.format("стоимость камер в сводке %.0f, сумма камер %.0f",
                        summary.num("chamber_construction_cost").orElse(0d), chamberCost));

        session.check(ComplianceRule.TIE_IN_COST, vid, List.of(summary.str("id")),
                Math.abs(tieInCost - tieInCount * catalog.tieInCost()) < 1,
                () -> String.format("%d врезок должны стоить %.0f, в сводке %.0f",
                        tieInCount, tieInCount * catalog.tieInCost(), tieInCost));

        double expectedConstruction = segmentCost + chamberCost + tieInCost;
        session.check(ComplianceRule.SUMMARY_SUMS, vid, List.of(summary.str("id")),
                Math.abs(construction - expectedConstruction) < MONEY_TOLERANCE,
                () -> String.format("стоимость строительства %.0f, а участки, камеры "
                        + "и врезки дают %.0f", construction, expectedConstruction));

        session.check(ComplianceRule.SUMMARY_SUMS, vid, List.of(summary.str("id")),
                Math.abs(total - (construction + penalty)) < MONEY_TOLERANCE,
                () -> String.format("итоговая стоимость %.0f не равна сумме строительства "
                        + "и штрафа %.0f", total, construction + penalty));

        session.check(ComplianceRule.SUMMARY_SUMS, vid, List.of(summary.str("id")),
                Math.abs(declaredLength - totalLength)
                        <= Math.max(LENGTH_TOLERANCE_M, totalLength * 0.002),
                () -> String.format("длина новой сети в сводке %.2f м, сумма участков %.2f м",
                        declaredLength, totalLength));

        double expectedScore = catalog.score(total, declaredLength);
        session.check(ComplianceRule.SCORE_FORMULA, vid, List.of(summary.str("id")),
                Math.abs(expectedScore - summary.num("score").orElse(0d)) < SCORE_TOLERANCE,
                () -> String.format("показатель %.3f, по формуле выходит %.3f",
                        summary.num("score").orElse(0d), expectedScore));

        if (scene == null) {
            return;
        }

        Set<String> unconnected = new LinkedHashSet<>();
        Object ids = summary.properties().get("unconnected_oks_ids");
        if (ids instanceof List) {
            for (Object item : (List<?>) ids) {
                unconnected.add(String.valueOf(item));
            }
        }

        double expectedPenalty = 0;
        for (FutureOks oks : scene.getFutureOks()) {
            if (unconnected.contains(oks.getConnectionPointId())
                    || unconnected.contains(oks.getId())
                    || unconnected.contains(String.valueOf(oks.getRawConnectionPointId()))) {
                expectedPenalty += catalog.unconnectedPenalty(oks.getFlowTph());
            }
        }
        double expected = expectedPenalty;
        session.check(ComplianceRule.PENALTY_FORMULA, vid, List.of(summary.str("id")),
                Math.abs(expected - penalty) < 1,
                () -> String.format("штраф %.0f, по формуле выходит %.0f", penalty, expected));

        for (FutureOks oks : scene.getFutureOks()) {
            boolean listed = unconnected.contains(oks.getConnectionPointId())
                    || unconnected.contains(oks.getId())
                    || unconnected.contains(String.valueOf(oks.getRawConnectionPointId()));
            boolean reached = parentOf.containsKey(oks.getConnectionPointId())
                    || parentOf.containsKey(oks.getId());
            session.check(ComplianceRule.UNCONNECTED_CONSISTENT, vid,
                    List.of(oks.getConnectionPointId()), !(listed && reached),
                    () -> "точка " + oks.getConnectionPointId()
                            + " и подключена, и указана неподключённой");
            session.check(ComplianceRule.UNCONNECTED_CONSISTENT, vid,
                    List.of(oks.getConnectionPointId()), listed || reached,
                    () -> "точка " + oks.getConnectionPointId()
                            + " не подключена, но в списке неподключённых её нет");
        }
    }

    private void checkRanks(Session session, java.util.Collection<Variant> variants) {
        List<Integer> ranks = new ArrayList<>();
        for (Variant variant : variants) {
            for (RawFeature summary : variant.summaries) {
                ranks.add(summary.num("rank").orElse(0d).intValue());
            }
        }
        ranks.sort(Integer::compareTo);
        List<Integer> expected = new ArrayList<>();
        for (int i = 1; i <= ranks.size(); i++) {
            expected.add(i);
        }
        session.check(ComplianceRule.RANKS_SEQUENTIAL, null, List.of(),
                ranks.equals(expected),
                () -> "места вариантов " + ranks + ", ожидались " + expected);
    }

    // =================================================================================

    /** Угол поворота в вершине: отклонение от продолжения предыдущего звена, град. */
    private static double turnDeg(Coordinate a, Coordinate b, Coordinate c) {
        double inX = b.x - a.x;
        double inY = b.y - a.y;
        double outX = c.x - b.x;
        double outY = c.y - b.y;
        double inLength = Math.hypot(inX, inY);
        double outLength = Math.hypot(outX, outY);
        if (inLength < 1e-9 || outLength < 1e-9) {
            return 0;
        }
        double cos = (inX * outX + inY * outY) / (inLength * outLength);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    /** Объекты одного варианта, разложенные по типам. */
    private static final class Variant {
        private final String id;
        private final List<RawFeature> segments = new ArrayList<>();
        private final List<RawFeature> chambers = new ArrayList<>();
        private final List<RawFeature> nodes = new ArrayList<>();
        private final List<RawFeature> summaries = new ArrayList<>();
        private final Map<String, RawFeature> chamberById = new LinkedHashMap<>();

        private Variant(String id) {
            this.id = id;
        }

        private void add(String type, RawFeature feature) {
            switch (type) {
                case "heat_network":
                    segments.add(feature);
                    break;
                case "heat_chamber":
                    chambers.add(feature);
                    chamberById.put(feature.str("id"), feature);
                    break;
                case "technical_node":
                    nodes.add(feature);
                    break;
                case "variant_summary":
                    summaries.add(feature);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Счётчик проверок и накопитель находок. Отдельным объектом, чтобы число проверок
     * считалось само и не расходилось с тем, сколько их сделано на самом деле.
     */
    private static final class Session {
        private int checks;
        private final List<ComplianceFinding> findings = new ArrayList<>();

        private void check(ComplianceRule rule, String variantId, List<String> objectIds,
                           boolean condition, java.util.function.Supplier<String> detail) {
            checks++;
            if (!condition) {
                findings.add(new ComplianceFinding(rule, variantId, List.copyOf(objectIds),
                        detail.get()));
            }
        }
    }
}
