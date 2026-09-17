package ru.lct.heatroute.variant;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.routing.RoutingGraph;

import java.util.List;

/**
 * Устранение самопересечений новой сети.
 * <p>
 * Раздел 2.3 ТЗ: «Новые участки не должны пересекаться между собой вне общего узла.
 * Если два маршрута пересекаются, их нужно объединить в общую сеть либо перестроить.»
 * <p>
 * Дерево, построенное в графе маршрутизации, не имеет общих узлов между ветвями,
 * но в плоскости две его ветви всё равно могут пересечься: граф видимости этого
 * не запрещает. На конкурсном наборе таких пересечений оказалось семь на три варианта.
 * <p>
 * Правило исправления прямо следует из формулировки ТЗ — объединить. В точке пересечения
 * создаётся узел, ребро одной ветви делится им на два, а вторая ветвь переподчиняется
 * этому узлу. Пересечение исчезает, дерево остаётся деревом, а сеть становится короче:
 * пересечение двух ветвей само по себе означает, что часть пути пройдена дважды.
 */
@Slf4j
@Component
public class CrossingRepair {

    /** Предел проходов: каждое исправление может породить новое пересечение. */
    private static final int MAX_PASSES = 40;

    /**
     * Если точка пересечения ближе этого расстояния к существующему узлу, новый узел
     * не создаётся — ветвь подключается к существующему. Иначе появляются участки
     * в считанные сантиметры, а это та же «мелкая ломаная», от которой мы уходим.
     */
    private static final double SNAP_TOLERANCE_M = 1.0;

    private final ReferenceCatalog catalog;

    public CrossingRepair(ReferenceCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Убирает пересечения ветвей.
     *
     * @param passable проверка проходимости прямого отрезка между двумя узлами дерева.
     *                 Нужна не везде: деление ребра точкой пересечения даёт отрезки,
     *                 лежащие внутри исходных, и они заведомо проходимы. А вот
     *                 подключение к соседнему узлу вместо деления создаёт отрезок,
     *                 которого в графе не было, и его клиренс надо проверять.
     * @return число устранённых пересечений
     */
    public int repair(RouteTree tree, RoutingGraph graph,
                      java.util.function.BiPredicate<Integer, Integer> passable) {
        int fixed = 0;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            if (!repairOnce(tree, graph, passable)) {
                break;
            }
            fixed++;
        }
        if (fixed > 0) {
            log.debug("Устранено самопересечений трассы: {}", fixed);
        }
        return fixed;
    }

    private boolean repairOnce(RouteTree tree, RoutingGraph graph,
                               java.util.function.BiPredicate<Integer, Integer> passable) {
        List<int[]> edges = tree.edges();
        RobustLineIntersector intersector = new RobustLineIntersector();
        int maxDegree = catalog.props().getMaxChamberDegree();

        for (int i = 0; i < edges.size(); i++) {
            for (int j = i + 1; j < edges.size(); j++) {
                int[] a = edges.get(i);
                int[] b = edges.get(j);
                if (sharesNode(a, b)) {
                    continue;
                }

                Coordinate a1 = tree.locationOf(graph, a[0]);
                Coordinate a2 = tree.locationOf(graph, a[1]);
                Coordinate b1 = tree.locationOf(graph, b[0]);
                Coordinate b2 = tree.locationOf(graph, b[1]);

                intersector.computeIntersection(a1, a2, b1, b2);
                if (!intersector.hasIntersection()) {
                    continue;
                }
                // Совпадение по отрезку, а не в точке, разбором не лечится: такие
                // рёбра накладываются друг на друга и требуют перестроения ветви.
                // На практике не встречается, но молча делить их нельзя.
                if (intersector.getIntersectionNum() != 1) {
                    continue;
                }
                Coordinate point = intersector.getIntersection(0);

                // Пересечение ровно в концах рёбер узлами не является: там ветви
                // просто сходятся, и делить нечего.
                if (isEndpoint(point, a1, a2) && isEndpoint(point, b1, b2)) {
                    continue;
                }

                if (merge(tree, graph, a, b, point, maxDegree, passable)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Объединяет две ветви в точке пересечения.
     * <p>
     * Делится ребро {@code split}, а конец ребра {@code moved} переподчиняется новому узлу.
     * Направление выбирается так, чтобы не образовался цикл: узел деления лежит
     * в поддереве делимого ребра, поэтому переносить можно только ту ветвь,
     * которая этому поддереву не принадлежит.
     */
    private boolean merge(RouteTree tree, RoutingGraph graph, int[] first, int[] second,
                          Coordinate point, int maxDegree,
                          java.util.function.BiPredicate<Integer, Integer> passable) {
        if (tryMerge(tree, graph, first, second, point, maxDegree, passable)) {
            return true;
        }
        return tryMerge(tree, graph, second, first, point, maxDegree, passable);
    }

    private boolean tryMerge(RouteTree tree, RoutingGraph graph, int[] split, int[] moved,
                             Coordinate point, int maxDegree,
                             java.util.function.BiPredicate<Integer, Integer> passable) {
        int splitParent = split[0];
        int splitChild = split[1];
        int movedChild = moved[1];

        // Делимое ребро не должно лежать в поддереве переносимой ветви: иначе
        // переподчинение замкнёт цикл.
        if (tree.isDescendant(splitChild, movedChild)) {
            return false;
        }

        // Точка пересечения почти совпала с концом делимого ребра — подключаемся
        // к существующему узлу вместо создания нового.
        Integer existing = nearbyNode(tree, graph, point, splitParent, splitChild);
        if (existing != null) {
            if (tree.isDescendant(existing, movedChild)) {
                return false;
            }
            if (tree.degree(existing) + 1 > maxDegree) {
                return false;
            }
            // Отрезок «существующий узел — перенесённая ветвь» в графе не строился,
            // поэтому его проходимость проверяется отдельно.
            if (!passable.test(existing, movedChild)) {
                return false;
            }
            tree.reparent(movedChild, existing);
            return true;
        }

        // Новый узел получает три примыкания: к родителю, к прежнему потомку
        // и к перенесённой ветви.
        if (3 > maxDegree) {
            return false;
        }
        int node = tree.addSyntheticNode(point);
        tree.reparent(node, splitParent);
        tree.reparent(splitChild, node);
        tree.reparent(movedChild, node);
        return true;
    }

    /** Конец делимого ребра, если точка пересечения к нему достаточно близка. */
    private Integer nearbyNode(RouteTree tree, RoutingGraph graph, Coordinate point,
                               int parent, int child) {
        if (tree.locationOf(graph, parent).distance(point) <= SNAP_TOLERANCE_M) {
            return parent;
        }
        if (tree.locationOf(graph, child).distance(point) <= SNAP_TOLERANCE_M) {
            return child;
        }
        return null;
    }

    private boolean sharesNode(int[] a, int[] b) {
        return a[0] == b[0] || a[0] == b[1] || a[1] == b[0] || a[1] == b[1];
    }

    private boolean isEndpoint(Coordinate point, Coordinate a, Coordinate b) {
        return point.distance(a) < 1e-6 || point.distance(b) < 1e-6;
    }
}
