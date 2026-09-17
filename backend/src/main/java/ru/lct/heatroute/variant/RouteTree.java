package ru.lct.heatroute.variant;

import lombok.Getter;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatroute.routing.RoutingGraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Дерево новой тепловой сети одной независимой части: корень в точке врезки,
 * листья в точках подключения ОКС.
 * <p>
 * Структура сознательно держится на узлах графа маршрутизации, а не на геометрии:
 * пока идут перестроения и локальные улучшения, дешевле двигать ссылки, чем
 * пересобирать линии. Геометрия появляется один раз, на выходе.
 * <p>
 * Требование раздела 2.2 ТЗ «от точки врезки до каждого подключённого ОКС должен быть
 * только один путь» выполняется по построению: у каждого узла ровно один родитель.
 */
@Getter
public class RouteTree {

    /** Корень — узел графа, соответствующий точке врезки. */
    private final int root;

    /** Родитель каждого узла дерева; у корня родителя нет. */
    private final Map<Integer, Integer> parent = new LinkedHashMap<>();

    /** Дети каждого узла в порядке добавления. */
    private final Map<Integer, List<Integer>> children = new LinkedHashMap<>();

    /** Узлы-терминалы и расход соответствующего ОКС, т/ч. */
    private final Map<Integer, Double> terminalFlow = new LinkedHashMap<>();

    /** ID перспективного ОКС по узлу-терминалу. */
    private final Map<Integer, String> terminalOks = new LinkedHashMap<>();

    public RouteTree(int root) {
        this.root = root;
        children.put(root, new ArrayList<>());
    }

    public Set<Integer> nodes() {
        Set<Integer> all = new LinkedHashSet<>();
        all.add(root);
        all.addAll(parent.keySet());
        return all;
    }

    public boolean contains(int node) {
        return node == root || parent.containsKey(node);
    }

    /**
     * Прикрепляет путь к дереву. Первый узел пути обязан уже принадлежать дереву,
     * последний становится листом. Промежуточные узлы — будущие развилки и повороты.
     */
    public void attachPath(int[] path) {
        if (path.length == 0) {
            return;
        }
        if (!contains(path[0])) {
            throw new IllegalArgumentException(
                    "Путь должен начинаться в узле, уже принадлежащем дереву: " + path[0]);
        }
        for (int i = 1; i < path.length; i++) {
            int node = path[i];
            if (contains(node)) {
                continue;   // путь вышел на уже построенную часть дерева
            }
            parent.put(node, path[i - 1]);
            children.computeIfAbsent(path[i - 1], k -> new ArrayList<>()).add(node);
            children.computeIfAbsent(node, k -> new ArrayList<>());
        }
    }

    public void markTerminal(int node, String oksId, double flowTph) {
        terminalOks.put(node, oksId);
        terminalFlow.put(node, flowTph);
    }

    public List<Integer> childrenOf(int node) {
        return children.getOrDefault(node, Collections.emptyList());
    }

    /** Степень узла в дереве: родитель плюс дети. */
    public int degree(int node) {
        return (node == root ? 0 : 1) + childrenOf(node).size();
    }

    /** Узел является развилкой, то есть требует тепловой камеры (раздел 3 ТП). */
    public boolean isBranch(int node) {
        return childrenOf(node).size() >= 2;
    }

    /** Узлы в порядке обхода от корня: родитель всегда раньше своих детей. */
    public List<Integer> preOrder() {
        List<Integer> out = new ArrayList<>();
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            int n = stack.pop();
            out.add(n);
            List<Integer> kids = childrenOf(n);
            for (int i = kids.size() - 1; i >= 0; i--) {
                stack.push(kids.get(i));
            }
        }
        return out;
    }

    /**
     * Расход в каждом узле: сумма расходов ОКС его поддерева.
     * Расход участка равен расходу его нижнего узла — по этому участку тепло идёт
     * ко всем объектам поддерева (раздел 3 ТП).
     */
    public Map<Integer, Double> subtreeFlows() {
        Map<Integer, Double> flow = new LinkedHashMap<>();
        List<Integer> order = preOrder();
        for (int i = order.size() - 1; i >= 0; i--) {
            int node = order.get(i);
            double sum = terminalFlow.getOrDefault(node, 0d);
            for (int child : childrenOf(node)) {
                sum += flow.getOrDefault(child, 0d);
            }
            flow.put(node, sum);
        }
        return flow;
    }

    /** Суммарный расход всего дерева — расход в точке врезки. */
    public double totalFlow() {
        return terminalFlow.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    /** Геометрическая длина дерева, м. */
    public double totalLength(RoutingGraph graph) {
        double sum = 0;
        for (Map.Entry<Integer, Integer> e : parent.entrySet()) {
            sum += graph.node(e.getKey()).getLocation()
                    .distance(graph.node(e.getValue()).getLocation());
        }
        return sum;
    }

    public List<String> connectedOks() {
        return new ArrayList<>(terminalOks.values());
    }

    /**
     * Удаляет проходные узлы: узел без развилки, не терминал и не корень, лежащий
     * на прямой между соседями, исчезает, а его ребёнок прикрепляется к родителю.
     * Это и есть борьба со «случайной мелкой ломаной» из критериев оценки:
     * граф видимости даёт вершины на каждом обогнутом угле, но часть из них
     * оказывается на одной прямой после того, как дерево собрано целиком.
     *
     * Спрямление никогда не выполняется вслепую: новый прямой отрезок между соседями
     * обязан быть проходимым, иначе срезанный угол нарушит клиренс. Проверку
     * предоставляет вызывающий — только он знает, чей собственный контур исключается.
     *
     * @param maxTurnDeg угол поворота, ниже которого узел считается проходным
     * @param passable   проверка проходимости прямого отрезка между двумя узлами
     * @return сколько узлов удалено
     */
    public int straighten(RoutingGraph graph, double maxTurnDeg,
                          java.util.function.BiPredicate<Integer, Integer> passable) {
        int removed = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Integer node : new ArrayList<>(parent.keySet())) {
                if (terminalOks.containsKey(node) || node == root) {
                    continue;
                }
                List<Integer> kids = childrenOf(node);
                if (kids.size() != 1) {
                    continue;
                }
                int p = parent.get(node);
                int c = kids.get(0);
                Coordinate cp = graph.node(p).getLocation();
                Coordinate cn = graph.node(node).getLocation();
                Coordinate cc = graph.node(c).getLocation();

                double turn = 180 - angleDeg(cp, cn, cc);
                if (turn > maxTurnDeg) {
                    continue;
                }
                if (!passable.test(p, c)) {
                    continue;
                }
                parent.put(c, p);
                children.get(p).remove((Integer) node);
                children.get(p).add(c);
                parent.remove(node);
                children.remove(node);
                removed++;
                changed = true;
            }
        }
        return removed;
    }

    /** Угол при вершине {@code b} в градусах, {@code [0, 180]}. */
    private static double angleDeg(Coordinate a, Coordinate b, Coordinate c) {
        double ax = a.x - b.x;
        double ay = a.y - b.y;
        double cx = c.x - b.x;
        double cy = c.y - b.y;
        double la = Math.hypot(ax, ay);
        double lc = Math.hypot(cx, cy);
        if (la == 0 || lc == 0) {
            return 180;
        }
        double cos = (ax * cx + ay * cy) / (la * lc);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }
}
