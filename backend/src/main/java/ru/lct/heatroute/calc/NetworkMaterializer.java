package ru.lct.heatroute.calc;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.domain.reference.ReferenceProperties.DiameterRow;
import ru.lct.heatroute.domain.result.LayingMethod;
import ru.lct.heatroute.domain.result.NewChamberResult;
import ru.lct.heatroute.domain.result.NewSegment;
import ru.lct.heatroute.domain.result.TechnicalNodeResult;
import ru.lct.heatroute.geo.Geo;
import ru.lct.heatroute.routing.ObstacleField;
import ru.lct.heatroute.routing.RoutingGraph;
import ru.lct.heatroute.variant.RouteTree;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Превращение дерева маршрутов в участки новой сети с расходами, условными диаметрами,
 * способом прокладки, камерами, техническими узлами и стоимостью.
 * <p>
 * Раздел 2.3 ТП в редакции от 18.09 задаёт подбор диаметра строже, чем первая редакция,
 * и три его требования определили устройство этого класса:
 * <ul>
 *   <li>«На пути между узлами, в которых меняется расчётный расход, выбранный ДУ
 *       сохраняется на всей длине» — значит диаметр нельзя менять посреди перегона.
 *       Поэтому дерево сначала разбирается на перегоны между узлами смены расхода,
 *       и диаметр назначается перегону целиком, а не по мере накопления длины;</li>
 *   <li>«Предельная длина проверяется отдельно по каждому непрерывному пути. Общий
 *       участок разветвлённой сети учитывается в каждом соответствующем пути. Длины
 *       параллельных ветвей между собой не суммируются» — значит предел проверяется
 *       по путям от места присоединения до каждой точки подключения, а не по дереву
 *       в целом. Если путь не укладывается, повышается диаметр всего непрерывного
 *       участка одного ДУ: «изменять ДУ только для начала нового отсчёта нельзя»;</li>
 *   <li>«По направлению от точки подключения к месту присоединения условный диаметр
 *       новой сети не должен уменьшаться» — инвариант, который поддерживается явно:
 *       диаметр родительского перегона не меньше диаметра любого из дочерних.</li>
 * </ul>
 * Эти три правила тянут друг друга: повышение диаметра по предельной длине может
 * нарушить монотонность, а выравнивание монотонности склеивает соседние перегоны
 * в более длинный непрерывный участок одного ДУ и снова упирается в предел. Поэтому
 * назначение диаметров — небольшой цикл до стабилизации; диаметры в нём только растут,
 * так что цикл конечен.
 * <p>
 * У одного участка выгрузки один набор расчётных параметров. Диаметр внутри перегона
 * уже постоянен, поэтому делить линию остаётся только по границам специальных проходов,
 * и в точке деления появляется технический узел (раздел 4 ТП).
 */
@Slf4j
@Component
public class NetworkMaterializer {

    /**
     * Ограничение на число проходов назначения диаметров. Диаметры только растут
     * и ступеней в справочнике восемнадцать, поэтому до предела дело дойти не должно;
     * он стоит как страховка от бесконечного цикла на неожиданных данных.
     */
    private static final int MAX_DIAMETER_PASSES = 256;

    private final ReferenceCatalog catalog;
    private final TurnLimiter turnLimiter;

    public NetworkMaterializer(ReferenceCatalog catalog, TurnLimiter turnLimiter) {
        this.catalog = catalog;
        this.turnLimiter = turnLimiter;
    }

    /** Результат материализации одной независимой части новой сети. */
    @Value
    public static class Materialized {
        List<NewSegment> segments;
        List<NewChamberResult> chambers;
        List<TechnicalNodeResult> technicalNodes;
        /** Условный диаметр новой сети в месте присоединения, мм. */
        int rootDiameter;
        /** Расход, вносимый этой частью в существующую сеть, т/ч. */
        double rootFlow;
        /** ОКС, чей расход не покрывается наибольшим ДУ справочника. */
        List<String> overCapacityOks;
        /** Наибольший ДУ участков, примыкающих к каждому узлу-камере. */
        Map<String, Integer> chamberMaxDiameter;
        /**
         * Сколько поворотов круче 90° не удалось привести к пределу раздела 2.1 ТП.
         * Ноль — норма; всё остальное надо показывать, а не прятать.
         */
        int sharpTurns;
    }

    /** Счётчик идентификаторов выходных объектов в пределах варианта. */
    public static class IdSequence {
        private final String variantId;
        private int segment;
        private int chamber;
        private int node;
        private int tieIn;

        public IdSequence(String variantId) {
            this.variantId = variantId;
        }

        public String nextSegment() {
            return "new_" + variantId + "_" + (++segment);
        }

        public String nextChamber() {
            return "ch_" + variantId + "_" + (++chamber);
        }

        public String nextNode() {
            return "tn_" + variantId + "_" + (++node);
        }

        public String nextTieIn() {
            return "tie_" + variantId + "_" + (++tieIn);
        }
    }

    /**
     * Перегон: путь дерева между двумя соседними узлами, в которых меняется расчётный
     * расход. Внутри перегона расход постоянен, поэтому и условный диаметр один.
     * Промежуточные узлы перегона — обычные повороты, они остаются вершинами линии.
     */
    private static final class Stretch {
        final int parent;
        final List<Integer> nodes = new ArrayList<>();
        final List<Integer> children = new ArrayList<>();
        double flow;
        double length;
        int dn;

        Stretch(int parent) {
            this.parent = parent;
        }

        int startNode() {
            return nodes.get(0);
        }

        int endNode() {
            return nodes.get(nodes.size() - 1);
        }
    }

    /** Накопление одного выходного участка вдоль перегона. */
    private static final class OpenSegment {
        String startNodeId;
        final List<Coordinate> coords = new ArrayList<>();
        double flow;
        int dn;
        LayingMethod laying;
        double kSpecial;
        String crossingType;

        boolean sameParameters(LayingMethod laying, double kSpecial) {
            return this.laying == laying && Math.abs(this.kSpecial - kSpecial) < 1e-9;
        }
    }

    /**
     * @param rootNodeId  идентификатор узла в месте присоединения: существующая камера,
     *                    к которой примыкает новая сеть, либо новая камера
     * @param terminalIds идентификатор выходного узла по ID перспективного ОКС —
     *                    точка подключения из входных данных
     */
    public Materialized materialize(RouteTree tree,
                                    RoutingGraph graph,
                                    ObstacleField field,
                                    String variantId,
                                    String rootNodeId,
                                    Map<String, String> terminalIds,
                                    IdSequence ids) {
        return materialize(tree, graph, field, variantId, rootNodeId, terminalIds, ids,
                (from, to) -> false);
    }

    /**
     * @param blocked дополнительный запрет поверх поля препятствий: пользовательские
     *                запретные зоны и уже принятые части сети этого же варианта.
     *                Приведение угла поворота обязано их учитывать: сглаживающая дуга
     *                уходит в сторону от исходной вершины и без этой проверки могла бы
     *                зайти в зону, через которую трассе нельзя.
     */
    public Materialized materialize(RouteTree tree,
                                    RoutingGraph graph,
                                    ObstacleField field,
                                    String variantId,
                                    String rootNodeId,
                                    Map<String, String> terminalIds,
                                    IdSequence ids,
                                    TurnLimiter.Passability blocked) {
        Map<Integer, Double> flows = tree.subtreeFlows();
        List<String> overCapacity = new ArrayList<>();

        // --- разбор дерева на перегоны ----------------------------------------------------
        List<Stretch> stretches = buildStretches(tree, graph, flows);
        if (stretches.isEmpty()) {
            return new Materialized(List.of(), List.of(), List.of(), 0,
                    flows.getOrDefault(tree.getRoot(), 0d), List.of(), Map.of(), 0);
        }

        assignDiameters(stretches, tree, overCapacity);

        // --- идентификаторы структурных узлов --------------------------------------------
        Map<Integer, String> nodeIds = new LinkedHashMap<>();
        nodeIds.put(tree.getRoot(), rootNodeId);
        for (Stretch st : stretches) {
            int end = st.endNode();
            if (nodeIds.containsKey(end)) {
                continue;
            }
            String oksId = tree.getTerminalOks().get(end);
            if (oksId != null) {
                nodeIds.put(end, terminalIds.getOrDefault(oksId, oksId));
            } else {
                // Не терминал, а значит развилка: раздел 2.1 ТП разрешает разветвление
                // только в тепловой камере.
                nodeIds.put(end, ids.nextChamber());
            }
        }

        // --- участки, технические узлы -----------------------------------------------------
        List<NewSegment> segments = new ArrayList<>();
        List<TechnicalNodeResult> technicalNodes = new ArrayList<>();
        Map<String, Set<Integer>> chamberAdjacent = new LinkedHashMap<>();

        int sharpTurns = 0;
        for (Stretch st : stretches) {
            sharpTurns += emitStretch(st, tree, graph, field, variantId, nodeIds, ids,
                    segments, technicalNodes, chamberAdjacent, blocked);
        }
        if (sharpTurns > 0) {
            log.warn("Поворотов круче 90° осталось {}: предел раздела 2.1 ТП по ним "
                    + "не выдержан", sharpTurns);
        }

        // --- камеры на развилках ------------------------------------------------------------
        List<NewChamberResult> chambers = new ArrayList<>();
        Map<String, Integer> chamberMaxDn = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : nodeIds.entrySet()) {
            int node = e.getKey();
            if (node == tree.getRoot() || !tree.isBranch(node)
                    || tree.getTerminalOks().containsKey(node)) {
                continue;
            }
            String chamberId = e.getValue();
            int maxDn = maxAdjacent(chamberAdjacent, chamberId);
            chamberMaxDn.put(chamberId, maxDn);
            chambers.add(NewChamberResult.builder()
                    .id(chamberId)
                    .variantId(variantId)
                    .location(Geo.point(tree.locationOf(graph, node)))
                    .diameter(maxDn)
                    .degree(tree.degree(node))
                    .cost(catalog.chamberCost(maxDn))
                    .build());
        }

        int rootDn = 0;
        for (Stretch st : stretches) {
            if (st.parent < 0) {
                rootDn = Math.max(rootDn, st.dn);
            }
        }
        chamberMaxDn.put(rootNodeId, Math.max(rootDn, maxAdjacent(chamberAdjacent, rootNodeId)));

        return new Materialized(segments, chambers, technicalNodes, rootDn,
                flows.getOrDefault(tree.getRoot(), 0d), overCapacity, chamberMaxDn,
                sharpTurns);
    }

    // =================================================================================
    //  Разбор дерева на перегоны
    // =================================================================================

    private List<Stretch> buildStretches(RouteTree tree, RoutingGraph graph,
                                         Map<Integer, Double> flows) {
        List<Stretch> stretches = new ArrayList<>();
        // {структурный узел, первый узел перегона, индекс родительского перегона}
        Deque<int[]> pending = new ArrayDeque<>();
        for (int child : tree.childrenOf(tree.getRoot())) {
            pending.push(new int[]{tree.getRoot(), child, -1});
        }

        while (!pending.isEmpty()) {
            int[] task = pending.pop();
            int from = task[0];
            int first = task[1];
            int parentIndex = task[2];

            Stretch st = new Stretch(parentIndex);
            st.nodes.add(from);
            int current = first;
            while (true) {
                st.nodes.add(current);
                if (isStructural(tree, current)) {
                    break;
                }
                List<Integer> kids = tree.childrenOf(current);
                if (kids.isEmpty()) {
                    break;
                }
                current = kids.get(0);
            }

            st.flow = flows.getOrDefault(first, 0d);
            st.length = pathLength(tree, graph, st.nodes);

            int index = stretches.size();
            stretches.add(st);
            if (parentIndex >= 0) {
                stretches.get(parentIndex).children.add(index);
            }

            for (int kid : tree.childrenOf(current)) {
                pending.push(new int[]{current, kid, index});
            }
        }
        return stretches;
    }

    /**
     * Узел меняет расчётный расход: развилка или точка подключения. На таком узле
     * перегон заканчивается, и в выгрузке он становится концом участка.
     */
    private static boolean isStructural(RouteTree tree, int node) {
        return tree.isBranch(node) || tree.getTerminalOks().containsKey(node);
    }

    private static double pathLength(RouteTree tree, RoutingGraph graph, List<Integer> nodes) {
        double sum = 0;
        for (int i = 0; i + 1 < nodes.size(); i++) {
            sum += tree.locationOf(graph, nodes.get(i))
                    .distance(tree.locationOf(graph, nodes.get(i + 1)));
        }
        return sum;
    }

    // =================================================================================
    //  Назначение условных диаметров
    // =================================================================================

    private void assignDiameters(List<Stretch> stretches, RouteTree tree,
                                 List<String> overCapacity) {
        for (Stretch st : stretches) {
            st.dn = catalog.selectForFlow(st.flow)
                    .map(DiameterRow::getDn)
                    .orElseGet(() -> {
                        collectTerminals(stretches, st, tree, overCapacity);
                        return catalog.largest().getDn();
                    });
        }

        int pass = 0;
        while (pass++ < MAX_DIAMETER_PASSES) {
            boolean changed = enforceMonotonicity(stretches);
            if (!changed) {
                changed = raiseOverlongRun(stretches);
            }
            if (!changed) {
                return;
            }
        }
        log.warn("Назначение условных диаметров не стабилизировалось за {} проходов; "
                + "оставлены последние значения", MAX_DIAMETER_PASSES);
    }

    /**
     * Раздел 2.3 ТП: по направлению от точки подключения к месту присоединения условный
     * диаметр не должен уменьшаться. Дочерние перегоны созданы позже родительских,
     * поэтому обход с конца списка гарантированно идёт от точек подключения к месту
     * присоединения.
     */
    private boolean enforceMonotonicity(List<Stretch> stretches) {
        boolean changed = false;
        for (int i = stretches.size() - 1; i >= 0; i--) {
            Stretch st = stretches.get(i);
            for (int childIndex : st.children) {
                int childDn = stretches.get(childIndex).dn;
                if (childDn > st.dn) {
                    st.dn = childDn;
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * Находит первый непрерывный участок одного ДУ, который не укладывается в предельную
     * длину, и повышает диаметр этого участка целиком. Целиком — потому что менять ДУ
     * только для начала нового отсчёта приложение запрещает прямо.
     * <p>
     * После повышения границы непрерывных участков меняются, поэтому исправляется по
     * одному нарушению за проход, а поиск начинается заново.
     */
    private boolean raiseOverlongRun(List<Stretch> stretches) {
        for (int i = 0; i < stretches.size(); i++) {
            if (!stretches.get(i).children.isEmpty()) {
                continue;               // предел проверяется по путям до точек подключения
            }
            List<Stretch> path = pathFromRoot(stretches, i);
            int from = 0;
            while (from < path.size()) {
                int dn = path.get(from).dn;
                int to = from;
                double length = 0;
                while (to < path.size() && path.get(to).dn == dn) {
                    length += path.get(to).length;
                    to++;
                }
                if (length > catalog.maxRunLength(dn) + 1e-6) {
                    int upgraded = nextDnUp(dn);
                    if (upgraded == dn) {
                        log.warn("Непрерывный участок ДУ {} длиной {} м превышает предельную "
                                        + "длину {} м, а повышать диаметр дальше нечем",
                                dn, Math.round(length), Math.round(catalog.maxRunLength(dn)));
                    } else {
                        for (int k = from; k < to; k++) {
                            path.get(k).dn = upgraded;
                        }
                        return true;
                    }
                }
                from = to;
            }
        }
        return false;
    }

    /** Перегоны от места присоединения до указанного перегона включительно. */
    private static List<Stretch> pathFromRoot(List<Stretch> stretches, int index) {
        List<Stretch> reversed = new ArrayList<>();
        for (int cursor = index; cursor >= 0; cursor = stretches.get(cursor).parent) {
            reversed.add(stretches.get(cursor));
        }
        List<Stretch> path = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            path.add(reversed.get(i));
        }
        return path;
    }

    /** ОКС в поддереве перегона — чтобы сказать, чей расход не покрывается справочником. */
    private static void collectTerminals(List<Stretch> stretches, Stretch st,
                                         RouteTree tree, List<String> out) {
        Deque<Stretch> queue = new ArrayDeque<>();
        queue.add(st);
        while (!queue.isEmpty()) {
            Stretch current = queue.poll();
            String oks = tree.getTerminalOks().get(current.endNode());
            if (oks != null && !out.contains(oks)) {
                out.add(oks);
            }
            for (int childIndex : current.children) {
                queue.add(stretches.get(childIndex));
            }
        }
    }

    private int nextDnUp(int dn) {
        for (DiameterRow row : catalog.diameters()) {
            if (row.getDn() > dn) {
                return row.getDn();
            }
        }
        return dn;
    }

    // =================================================================================
    //  Выпуск участков
    // =================================================================================

    /**
     * Пишет участки одного перегона. Диаметр и расход внутри перегона постоянны,
     * поэтому линия делится только по границам специальных проходов.
     *
     * @return сколько поворотов круче 90° осталось на этом перегоне
     */
    private int emitStretch(Stretch st,
                            RouteTree tree,
                            RoutingGraph graph,
                            ObstacleField field,
                            String variantId,
                            Map<Integer, String> nodeIds,
                            IdSequence ids,
                            List<NewSegment> segments,
                            List<TechnicalNodeResult> technicalNodes,
                            Map<String, Set<Integer>> chamberAdjacent,
                            TurnLimiter.Passability blocked) {
        String startNodeId = nodeIds.get(st.startNode());
        String endNodeId = nodeIds.get(st.endNode());
        if (startNodeId == null || endNodeId == null) {
            log.warn("Перегон без идентификатора структурного узла пропущен: {} → {}",
                    st.startNode(), st.endNode());
            return 0;
        }

        // Раздел 2.1 ТП: поворот не круче 90°. Правка делается по вершинам нитки,
        // до деления на участки: делить надо уже приведённую геометрию.
        List<Coordinate> vertices = new ArrayList<>(st.nodes.size());
        for (int node : st.nodes) {
            vertices.add(tree.locationOf(graph, node));
        }
        String exemptOks = tree.getTerminalOks().get(st.endNode());
        TurnLimiter.Result limited = turnLimiter.limit(vertices,
                (from, to) -> field.isPassable(from, to, st.dn, exemptOks)
                        && !blocked.test(from, to));
        List<Coordinate> path = limited.getPath();

        OpenSegment open = new OpenSegment();
        open.startNodeId = startNodeId;
        open.flow = st.flow;
        open.dn = st.dn;

        for (int i = 0; i + 1 < path.size(); i++) {
            emitEdge(field, path.get(i), path.get(i + 1), st, open, ids, variantId,
                    segments, technicalNodes, chamberAdjacent);
        }

        closeSegment(open, path.get(path.size() - 1), endNodeId, variantId,
                segments, chamberAdjacent, ids);
        return limited.getUnresolved();
    }

    /**
     * Делит одно ребро по границам специальных проходов, дописывая части в открытый
     * участок и закрывая его там, где меняется способ прокладки или коэффициент.
     */
    private void emitEdge(ObstacleField field,
                          Coordinate from,
                          Coordinate to,
                          Stretch st,
                          OpenSegment open,
                          IdSequence ids,
                          String variantId,
                          List<NewSegment> segments,
                          List<TechnicalNodeResult> technicalNodes,
                          Map<String, Set<Integer>> chamberAdjacent) {
        double length = from.distance(to);
        if (length <= 0) {
            return;
        }

        List<ObstacleField.SpecialInterval> specials =
                field.specialIntervals(from, to, st.dn);

        TreeSet<Double> breaks = new TreeSet<>();
        breaks.add(0d);
        breaks.add(length);
        for (ObstacleField.SpecialInterval si : specials) {
            breaks.add(Math.max(0, si.getFromFraction() * length));
            breaks.add(Math.min(length, si.getToFraction() * length));
        }

        List<Double> cuts = new ArrayList<>(breaks);
        for (int i = 0; i + 1 < cuts.size(); i++) {
            double a = cuts.get(i);
            double b = cuts.get(i + 1);
            if (b - a < 1e-6) {
                continue;
            }
            double mid = (a + b) / 2;
            ObstacleField.SpecialInterval si = specialAt(specials, mid / length);
            LayingMethod laying = si == null ? LayingMethod.BASE : LayingMethod.SPECIAL;
            double k = si == null ? 1.0 : si.getKSpecial();
            String type = si == null ? null : si.getCrossingType();

            Coordinate pa = interpolate(from, to, a / length);
            Coordinate pb = interpolate(from, to, b / length);

            if (open.coords.isEmpty()) {
                open.coords.add(pa);
                open.laying = laying;
                open.kSpecial = k;
                open.crossingType = type;
            } else if (!open.sameParameters(laying, k)) {
                // Раздел 4 ТП: на границах специального прохода участок делится, и если
                // граница не совпадает с камерой или точкой подключения, в ней появляется
                // технический узел.
                String nodeId = ids.nextNode();
                technicalNodes.add(technicalNode(nodeId, variantId, pa,
                        laying == LayingMethod.SPECIAL
                                ? "начало специального прохода"
                                : "конец специального прохода"));
                closeSegment(open, pa, nodeId, variantId, segments, chamberAdjacent, ids);
                open.startNodeId = nodeId;
                open.coords.add(pa);
                open.laying = laying;
                open.kSpecial = k;
                open.crossingType = type;
            }
            open.coords.add(pb);
        }
    }

    private void closeSegment(OpenSegment open, Coordinate at, String endNodeId,
                              String variantId, List<NewSegment> segments,
                              Map<String, Set<Integer>> chamberAdjacent,
                              IdSequence ids) {
        if (open.coords.size() < 2) {
            open.coords.clear();
            return;
        }
        List<Coordinate> coords = dedupe(open.coords);
        if (coords.size() < 2) {
            open.coords.clear();
            return;
        }
        double length = 0;
        for (int i = 0; i + 1 < coords.size(); i++) {
            length += coords.get(i).distance(coords.get(i + 1));
        }
        double cost = length * catalog.newCostPerM(open.dn) * open.kSpecial;

        segments.add(NewSegment.builder()
                .id(ids.nextSegment())
                .variantId(variantId)
                .geometry(Geo.line(coords))
                .startNodeId(open.startNodeId)
                .endNodeId(endNodeId)
                .flowTph(round(open.flow, 3))
                .diameter(open.dn)
                .length(Geo.roundCm(length))
                .layingMethod(open.laying)
                .kSpecial(open.kSpecial)
                .specialCrossingType(open.crossingType)
                .depthStart(null)
                .depthEnd(null)
                .kDepth(1.0)
                .cost(Math.round(cost))
                .build());

        chamberAdjacent.computeIfAbsent(open.startNodeId, k -> new LinkedHashSet<>()).add(open.dn);
        chamberAdjacent.computeIfAbsent(endNodeId, k -> new LinkedHashSet<>()).add(open.dn);

        open.coords.clear();
    }

    private static int maxAdjacent(Map<String, Set<Integer>> adjacent, String nodeId) {
        return adjacent.getOrDefault(nodeId, Set.of()).stream()
                .mapToInt(Integer::intValue).max().orElse(0);
    }

    private TechnicalNodeResult technicalNode(String id, String variantId,
                                              Coordinate at, String reason) {
        return TechnicalNodeResult.builder()
                .id(id)
                .variantId(variantId)
                .location(Geo.point(at))
                .reason(reason)
                .build();
    }

    private static ObstacleField.SpecialInterval specialAt(
            List<ObstacleField.SpecialInterval> intervals, double fraction) {
        for (ObstacleField.SpecialInterval si : intervals) {
            if (fraction >= si.getFromFraction() && fraction <= si.getToFraction()) {
                return si;
            }
        }
        return null;
    }

    private static Coordinate interpolate(Coordinate a, Coordinate b, double t) {
        return new Coordinate(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
    }

    private static List<Coordinate> dedupe(List<Coordinate> coords) {
        List<Coordinate> out = new ArrayList<>(coords.size());
        for (Coordinate c : coords) {
            if (out.isEmpty() || out.get(out.size() - 1).distance(c) > 1e-6) {
                out.add(c);
            }
        }
        return out;
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
