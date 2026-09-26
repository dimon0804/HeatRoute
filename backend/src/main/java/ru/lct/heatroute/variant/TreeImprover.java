package ru.lct.heatroute.variant;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.routing.RoutingGraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Локальное улучшение построенного дерева.
 * <p>
 * Эвристика кратчайшего пути подключает объекты по одному и назад не оглядывается:
 * ветвь, проложенная третьей, могла бы идти иначе, если бы знала о пятой. Классический
 * приём — пройтись по готовому решению и попробовать перецепить каждую ветвь в место,
 * которое стало удобным позже.
 * <p>
 * Реализован один ход, зато самый результативный: ветвь отцепляется от родителя
 * и подключается к ближайшей точке оставшегося дерева — узлу или точке на участке,
 * где тогда ставится тепловая камера. Ход принимается, только если он удешевляет
 * решение и новый отвод проходим по клиренсу.
 * <p>
 * Критерий именно денежный, а не «короче». Первая версия принимала любое укорочение
 * и на конкурсном наборе сократила сеть на 95 м ценой четырёх лишних камер —
 * это минус 10 млн руб. за трубу и плюс 14 млн за камеры. Тепловая камера стоит
 * как 25–40 м трубы, поэтому врезка новой оправдана, только если она экономит больше.
 * <p>
 * Ход не меняет ни состав подключённых объектов, ни структуру поддеревьев, поэтому
 * расходы и диаметры после него пересчитываются теми же правилами, а связность
 * сохраняется по построению.
 */
@Slf4j
@Component
public class TreeImprover {

    /** Сколько проходов по всем ветвям делать максимум. */
    private static final int MAX_PASSES = 6;

    /** Выигрыш меньше этого в метрах не стоит перестроения. */
    private static final double MIN_GAIN_M = 1.0;

    /** Расход, по которому берётся диаметр, если ветвь ничего не питает. */
    private static final double FALLBACK_FLOW_TPH = 1.0;

    /** Ближе этого к концу участка новый узел не ставится — получился бы огрызок. */
    private static final double MIN_OFFSET_M = 1.0;

    private final ReferenceCatalog catalog;

    public TreeImprover(ReferenceCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * @param passable проверка клиренса для нового отвода
     * @return выигрыш в метрах и число ходов, проверенных последним проходом
     */
    public Effort improve(RouteTree tree, RoutingGraph graph,
                          SteinerTreeBuilder.Passability passable) {
        double totalGain = 0;
        int maxDegree = catalog.props().getMaxChamberDegree();
        int[] verified = {0};

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            verified[0] = 0;
            double gain = improveOnce(tree, graph, passable, maxDegree, verified);
            if (gain <= 0) {
                break;
            }
            totalGain += gain;
        }
        if (totalGain > 0) {
            log.debug("Локальное улучшение дерева: решение дешевле на {} руб.",
                    Math.round(totalGain));
        }
        log.debug("Перецепка ветвей: последний проход проверил {} ходов без улучшения",
                verified[0]);
        return new Effort(totalGain, verified[0]);
    }

    /**
     * На сколько метров должен укоротиться отвод, чтобы окупить новую тепловую камеру.
     * Считается по фактическому диаметру перемещаемой ветви: чем толще труба,
     * тем быстрее камера окупается.
     */
    private double chamberBreakEvenMeters(int diameter) {
        return catalog.chamberCost(diameter) / catalog.newCostPerM(diameter);
    }

    /** Условный диаметр ветви по расходу её поддерева. */
    private int diameterOf(Map<Integer, Double> flows, int node) {
        double flow = flows.getOrDefault(node, FALLBACK_FLOW_TPH);
        return catalog.selectForFlow(Math.max(flow, FALLBACK_FLOW_TPH))
                .map(row -> row.getDn())
                .orElse(catalog.largest().getDn());
    }

    private double improveOnce(RouteTree tree, RoutingGraph graph,
                               SteinerTreeBuilder.Passability passable, int maxDegree,
                               int[] verified) {
        // Расходы поддеревьев считаются один раз на проход: они меняются только
        // после принятого хода, а ход завершает проход.
        Map<Integer, Double> flows = tree.subtreeFlows();

        for (int[] edge : new ArrayList<>(tree.edges())) {
            int parent = edge[0];
            int child = edge[1];

            Coordinate childLocation = tree.locationOf(graph, child);
            double currentLength = tree.locationOf(graph, parent).distance(childLocation);
            if (currentLength <= MIN_GAIN_M) {
                continue;
            }

            Set<Integer> subtree = collectSubtree(tree, child);
            String exemptOks = tree.getTerminalOks().get(child);

            int diameter = diameterOf(flows, child);
            double costPerMeter = catalog.newCostPerM(diameter);
            // Врезка новой камеры окупается только сокращением отвода на эту величину.
            double chamberBreakEven = chamberBreakEvenMeters(diameter);

            // --- вариант 1: подключиться к существующему узлу вне поддерева ------------
            Integer bestNode = null;
            double bestNodeLength = currentLength - MIN_GAIN_M;
            for (int node : tree.nodes()) {
                if (subtree.contains(node) || node == parent) {
                    continue;
                }
                if (tree.getTerminalOks().containsKey(node)) {
                    continue;   // точка подключения ОКС ветвей от себя не даёт
                }
                if (tree.degree(node) + 1 > maxDegree) {
                    continue;
                }
                verified[0]++;
                double length = tree.locationOf(graph, node).distance(childLocation);
                if (length < bestNodeLength && passable.check(
                        tree.locationOf(graph, node), childLocation, exemptOks, diameter)) {
                    bestNodeLength = length;
                    bestNode = node;
                }
            }

            // --- вариант 2: новая камера на участке вне поддерева ----------------------
            // Порог жёстче: к сокращению трубы добавляется стоимость самой камеры.
            int[] bestEdge = null;
            Coordinate bestPoint = null;
            double edgeThreshold = currentLength - chamberBreakEven - MIN_GAIN_M;
            double bestEdgeLength = bestNode != null
                    ? Math.min(bestNodeLength, edgeThreshold) : edgeThreshold;
            for (int[] other : tree.edges()) {
                if (subtree.contains(other[1]) || (other[0] == parent && other[1] == child)) {
                    continue;
                }
                if (subtree.contains(other[0])) {
                    continue;
                }
                Coordinate a = tree.locationOf(graph, other[0]);
                Coordinate b = tree.locationOf(graph, other[1]);
                Coordinate point = project(a, b, childLocation);
                if (point.distance(a) < MIN_OFFSET_M || point.distance(b) < MIN_OFFSET_M) {
                    continue;
                }
                verified[0]++;
                double length = point.distance(childLocation);
                if (length < bestEdgeLength
                        && passable.check(point, childLocation, exemptOks, diameter)) {
                    bestEdgeLength = length;
                    bestEdge = other;
                    bestPoint = point;
                }
            }

            // --- применяем лучший из найденных ходов ------------------------------------
            if (bestEdge != null) {
                int chamber = tree.addSyntheticNode(bestPoint);
                tree.reparent(chamber, bestEdge[0]);
                tree.reparent(bestEdge[1], chamber);
                tree.reparent(child, chamber);
                return (currentLength - bestEdgeLength) * costPerMeter
                        - catalog.chamberCost(diameter);
            }
            if (bestNode != null) {
                tree.reparent(child, bestNode);
                return (currentLength - bestNodeLength) * costPerMeter;
            }
        }
        return 0;
    }

    /** Узлы поддерева, включая его корень: к ним переподключаться нельзя — будет цикл. */
    private Set<Integer> collectSubtree(RouteTree tree, int root) {
        Set<Integer> out = new LinkedHashSet<>();
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            int node = stack.pop();
            if (!out.add(node)) {
                continue;
            }
            tree.childrenOf(node).forEach(stack::push);
        }
        return out;
    }

    /** Проекция точки на отрезок; при выходе за пределы — ближайший конец. */
    private static Coordinate project(Coordinate a, Coordinate b, Coordinate p) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared <= 1e-12) {
            return new Coordinate(a.x, a.y);
        }
        double t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        return new Coordinate(a.x + t * dx, a.y + t * dy);
    }
}
