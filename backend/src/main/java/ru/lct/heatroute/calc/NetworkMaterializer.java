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
 * Здесь выполняются три правила, которые проверяются экспертами построчно:
 * <ul>
 *   <li>расход участка равен сумме расходов ОКС его поддерева, условный диаметр —
 *       минимальный с достаточной пропускной способностью (раздел 3 ТП);</li>
 *   <li>предельная длина считается для непрерывной части одного ДУ; камера отсчёт
 *       не прерывает, смена ДУ — прерывает. При исчерпании предела ДУ повышается
 *       на ступень и в этой точке ставится технический узел;</li>
 *   <li>у одного участка один набор расчётных параметров: где меняется ДУ, способ
 *       прокладки или коэффициент стоимости, линия делится, и в точке деления
 *       появляется технический узел (раздел 8.1 ТП).</li>
 * </ul>
 */
@Slf4j
@Component
public class NetworkMaterializer {

    private final ReferenceCatalog catalog;

    public NetworkMaterializer(ReferenceCatalog catalog) {
        this.catalog = catalog;
    }

    /** Результат материализации одной независимой части новой сети. */
    @Value
    public static class Materialized {
        List<NewSegment> segments;
        List<NewChamberResult> chambers;
        List<TechnicalNodeResult> technicalNodes;
        /** Условный диаметр новой сети в точке врезки, мм. */
        int rootDiameter;
        /** Расход, вносимый этой частью в существующую сеть, т/ч. */
        double rootFlow;
        /** ОКС, чей расход не покрывается наибольшим ДУ справочника. */
        List<String> overCapacityOks;
        /** Наибольший ДУ участков, примыкающих к каждому узлу-камере. */
        Map<String, Integer> chamberMaxDiameter;
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

    /** Накопление одного выходного участка по пути обхода дерева. */
    private static class OpenSegment {
        String startNodeId;
        final List<Coordinate> coords = new ArrayList<>();
        double flow;
        int dn;
        LayingMethod laying;
        double kSpecial;
        String crossingType;

        boolean sameParameters(int dn, LayingMethod laying, double kSpecial, double flow) {
            return this.dn == dn && this.laying == laying
                    && Math.abs(this.kSpecial - kSpecial) < 1e-9
                    && Math.abs(this.flow - flow) < 1e-9;
        }
    }

    /** Состояние отсчёта предельной длины вдоль непрерывной части одного ДУ. */
    @Value
    private static class RunState {
        int dn;
        double accumulated;
    }

    /**
     * @param rootNodeId  идентификатор узла в точке врезки: существующая камера,
     *                    в которую выполняется врезка, либо новая камера
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
        Map<Integer, Double> flows = tree.subtreeFlows();
        List<NewSegment> segments = new ArrayList<>();
        List<NewChamberResult> chambers = new ArrayList<>();
        List<TechnicalNodeResult> technicalNodes = new ArrayList<>();
        List<String> overCapacity = new ArrayList<>();
        Map<Integer, String> nodeIds = new LinkedHashMap<>();
        Map<String, Integer> chamberMaxDn = new LinkedHashMap<>();
        Map<String, Set<Integer>> chamberAdjacent = new LinkedHashMap<>();

        nodeIds.put(tree.getRoot(), rootNodeId);

        // --- идентификаторы структурных узлов --------------------------------------------
        for (int node : tree.preOrder()) {
            if (node == tree.getRoot()) {
                continue;
            }
            String oksId = tree.getTerminalOks().get(node);
            if (oksId != null) {
                nodeIds.put(node, terminalIds.getOrDefault(oksId, oksId));
            } else if (tree.isBranch(node)) {
                nodeIds.put(node, ids.nextChamber());
            }
        }

        int rootDn = 0;
        double rootFlow = flows.getOrDefault(tree.getRoot(), 0d);

        // --- обход дерева от врезки -------------------------------------------------------
        for (int child : tree.childrenOf(tree.getRoot())) {
            RunState run = initialRun(flows.get(child), overCapacity, tree, child);
            rootDn = Math.max(rootDn, run.getDn());
            OpenSegment open = newOpen(rootNodeId,
                    tree.locationOf(graph, tree.getRoot()), flows.get(child));
            descend(tree, graph, field, child, open, run, flows, nodeIds, ids, variantId,
                    segments, technicalNodes, overCapacity, chamberAdjacent);
        }

        // --- камеры на развилках ----------------------------------------------------------
        for (Map.Entry<Integer, String> e : nodeIds.entrySet()) {
            int node = e.getKey();
            if (node == tree.getRoot() || !tree.isBranch(node)) {
                continue;
            }
            String chamberId = e.getValue();
            Set<Integer> adjacent = chamberAdjacent.getOrDefault(chamberId, Set.of());
            int maxDn = adjacent.stream().mapToInt(Integer::intValue).max().orElse(0);
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
        chamberMaxDn.put(rootNodeId,
                chamberAdjacent.getOrDefault(rootNodeId, Set.of()).stream()
                        .mapToInt(Integer::intValue).max().orElse(rootDn));

        return new Materialized(segments, chambers, technicalNodes, rootDn, rootFlow,
                overCapacity, chamberMaxDn);
    }

    // =================================================================================

    private void descend(RouteTree tree,
                         RoutingGraph graph,
                         ObstacleField field,
                         int node,
                         OpenSegment open,
                         RunState run,
                         Map<Integer, Double> flows,
                         Map<Integer, String> nodeIds,
                         IdSequence ids,
                         String variantId,
                         List<NewSegment> segments,
                         List<TechnicalNodeResult> technicalNodes,
                         List<String> overCapacity,
                         Map<String, Set<Integer>> chamberAdjacent) {
        int parent = tree.getParent().get(node);
        Coordinate from = tree.locationOf(graph, parent);
        Coordinate to = tree.locationOf(graph, node);
        double flow = flows.getOrDefault(node, 0d);

        RunState afterEdge = emitEdge(field, from, to, flow, run, open, ids, variantId,
                segments, technicalNodes, chamberAdjacent, nodeIds, parent);

        String structuralId = nodeIds.get(node);
        boolean structural = structuralId != null;

        if (structural) {
            closeSegment(open, to, structuralId, variantId, segments, chamberAdjacent, ids);
            for (int child : tree.childrenOf(node)) {
                double childFlow = flows.getOrDefault(child, 0d);
                RunState childRun = adjustRun(afterEdge, childFlow, overCapacity, tree, child);
                OpenSegment childOpen = newOpen(structuralId, to, childFlow);
                descend(tree, graph, field, child, childOpen, childRun, flows, nodeIds, ids,
                        variantId, segments, technicalNodes, overCapacity, chamberAdjacent);
            }
        } else {
            List<Integer> kids = tree.childrenOf(node);
            if (kids.isEmpty()) {
                // Лист без идентификатора структурного узла возникнуть не должен:
                // все листья — терминалы. Если возник, закрываем техническим узлом,
                // чтобы участок не остался без конечного узла в выгрузке.
                String id = ids.nextNode();
                technicalNodes.add(technicalNode(id, variantId, to, "конец ветви"));
                closeSegment(open, to, id, variantId, segments, chamberAdjacent, ids);
                return;
            }
            descend(tree, graph, field, kids.get(0), open, afterEdge, flows, nodeIds, ids,
                    variantId, segments, technicalNodes, overCapacity, chamberAdjacent);
        }
    }

    /**
     * Разбивает одно ребро дерева на атомарные части по смене условного диаметра
     * (предельная длина) и по границам специальных проходов, дописывая их в открытый
     * участок и закрывая его там, где меняются расчётные параметры.
     */
    private RunState emitEdge(ObstacleField field,
                              Coordinate from,
                              Coordinate to,
                              double flow,
                              RunState run,
                              OpenSegment open,
                              IdSequence ids,
                              String variantId,
                              List<NewSegment> segments,
                              List<TechnicalNodeResult> technicalNodes,
                              Map<String, Set<Integer>> chamberAdjacent,
                              Map<Integer, String> nodeIds,
                              int parentNode) {
        double length = from.distance(to);
        if (length <= 0) {
            return run;
        }

        // 1. Части по условному диаметру с учётом предельной длины.
        List<double[]> dnPieces = new ArrayList<>();   // {fromLen, toLen, dn}
        RunState current = run;
        double pos = 0;
        while (pos < length - 1e-9) {
            double capacityLeft = catalog.maxRunLength(current.getDn()) - current.getAccumulated();
            if (capacityLeft <= 1e-9) {
                int upgraded = nextDnUp(current.getDn());
                if (upgraded == current.getDn()) {
                    // Наибольший ДУ справочника исчерпан: дальше повышать нечем.
                    // Оставшаяся длина проходит на нём, ограничение помечается в отчёте.
                    dnPieces.add(new double[]{pos, length, current.getDn()});
                    current = new RunState(current.getDn(),
                            current.getAccumulated() + (length - pos));
                    pos = length;
                    break;
                }
                current = new RunState(upgraded, 0);
                continue;
            }
            double take = Math.min(length - pos, capacityLeft);
            dnPieces.add(new double[]{pos, pos + take, current.getDn()});
            current = new RunState(current.getDn(), current.getAccumulated() + take);
            pos += take;
        }

        // 2. Границы специальных проходов.
        List<ObstacleField.SpecialInterval> specials =
                field.specialIntervals(from, to, dnPieces.isEmpty()
                        ? run.getDn() : (int) dnPieces.get(0)[2]);

        // 3. Общий набор точек деления.
        TreeSet<Double> breaks = new TreeSet<>();
        breaks.add(0d);
        breaks.add(length);
        for (double[] piece : dnPieces) {
            breaks.add(piece[0]);
            breaks.add(piece[1]);
        }
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
            int dn = dnAt(dnPieces, mid, run.getDn());
            ObstacleField.SpecialInterval si = specialAt(specials, mid / length);
            LayingMethod laying = si == null ? LayingMethod.BASE : LayingMethod.SPECIAL;
            double k = si == null ? 1.0 : si.getKSpecial();
            String type = si == null ? null : si.getCrossingType();

            Coordinate pa = interpolate(from, to, a / length);
            Coordinate pb = interpolate(from, to, b / length);

            if (open.coords.isEmpty()) {
                open.coords.add(pa);
                open.flow = flow;
                open.dn = dn;
                open.laying = laying;
                open.kSpecial = k;
                open.crossingType = type;
            } else if (!open.sameParameters(dn, laying, k, flow)) {
                // Раздел 8.1 ТП: у одного участка один набор параметров. Смена ДУ,
                // способа прокладки или коэффициента стоимости делит линию,
                // и в точке деления появляется технический узел.
                String nodeId = ids.nextNode();
                technicalNodes.add(technicalNode(nodeId, variantId, pa,
                        reasonFor(open, dn, laying, k)));
                closeSegment(open, pa, nodeId, variantId, segments, chamberAdjacent, ids);
                open.startNodeId = nodeId;
                open.coords.add(pa);
                open.flow = flow;
                open.dn = dn;
                open.laying = laying;
                open.kSpecial = k;
                open.crossingType = type;
            }
            open.coords.add(pb);
        }
        return current;
    }

    private String reasonFor(OpenSegment open, int dn, LayingMethod laying, double k) {
        if (open.dn != dn) {
            return String.format("смена условного диаметра %d → %d мм", open.dn, dn);
        }
        if (open.laying != laying) {
            return laying == LayingMethod.SPECIAL
                    ? "начало специального прохода" : "конец специального прохода";
        }
        if (Math.abs(open.kSpecial - k) > 1e-9) {
            return String.format("смена коэффициента специального прохода %.2f → %.2f",
                    open.kSpecial, k);
        }
        return "смена расчётных параметров участка";
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

    private OpenSegment newOpen(String startNodeId, Coordinate start, double flow) {
        OpenSegment open = new OpenSegment();
        open.startNodeId = startNodeId;
        open.flow = flow;
        return open;
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

    private RunState initialRun(double flow, List<String> overCapacity,
                                RouteTree tree, int node) {
        return new RunState(diameterFor(flow, overCapacity, tree, node), 0);
    }

    /**
     * Условный диаметр для нового расхода. Если он ниже текущего в отсчёте — начинается
     * новый отсчёт предельной длины, потому что диаметр изменился (раздел 3 ТП).
     */
    private RunState adjustRun(RunState run, double flow, List<String> overCapacity,
                               RouteTree tree, int node) {
        int dn = diameterFor(flow, overCapacity, tree, node);
        if (dn == run.getDn()) {
            return run;
        }
        return new RunState(dn, 0);
    }

    private int diameterFor(double flow, List<String> overCapacity, RouteTree tree, int node) {
        return catalog.selectForFlow(flow)
                .map(DiameterRow::getDn)
                .orElseGet(() -> {
                    String oks = tree.getTerminalOks().get(node);
                    if (oks != null) {
                        overCapacity.add(oks);
                    }
                    return catalog.largest().getDn();
                });
    }

    private int nextDnUp(int dn) {
        List<DiameterRow> all = catalog.diameters();
        for (DiameterRow row : all) {
            if (row.getDn() > dn) {
                return row.getDn();
            }
        }
        return dn;
    }

    private static int dnAt(List<double[]> pieces, double pos, double fallback) {
        for (double[] piece : pieces) {
            if (pos >= piece[0] - 1e-9 && pos <= piece[1] + 1e-9) {
                return (int) piece[2];
            }
        }
        return (int) fallback;
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
