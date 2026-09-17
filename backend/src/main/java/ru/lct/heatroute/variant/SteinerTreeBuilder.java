package ru.lct.heatroute.variant;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.routing.RoutingGraph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Построение дерева новой сети эвристикой кратчайшего пути (Такахаси–Мацуяма).
 * <p>
 * Формально требуется дерево Штейнера с корнем в точке врезки и терминалами в точках
 * подключения ОКС. Задача NP-трудна, точное решение на семнадцати терминалах в графе
 * из тысячи узлов неприемлемо по времени. Эвристика кратчайшего пути даёт результат
 * с известной гарантией — не хуже удвоенного оптимума — и, что важнее для кейса,
 * строит именно дерево с общими участками: ветви, идущие в одну сторону, срастаются
 * сами собой, а это и есть «совместное подключение ОКС» из требований.
 * <p>
 * Ограничение на число участков у тепловой камеры (раздел 3 ТП: один к источнику
 * и не более трёх в остальных направлениях) встроено в само построение, а не добавлено
 * постобработкой. Узел, исчерпавший предел, исключается и из источников поиска,
 * и из транзита: новая ветвь физически не может через него пройти. Геометрическая
 * правка уже построенного дерева здесь хуже — она либо двигает трассу вслепую,
 * либо требует выдумывать положение дополнительной камеры.
 */
@Slf4j
@Component
public class SteinerTreeBuilder {

    private final ReferenceCatalog catalog;

    public SteinerTreeBuilder(ReferenceCatalog catalog) {
        this.catalog = catalog;
    }

    /** Терминал: узел графа, ОКС и его расчётный расход. */
    @Value
    public static class Terminal {
        int nodeIndex;
        String oksId;
        double flowTph;
    }

    /** Результат построения: дерево и терминалы, до которых не нашлось пути. */
    @Value
    public static class Result {
        RouteTree tree;
        List<String> unreachableOks;

        public boolean isEmpty() {
            return tree.getTerminalOks().isEmpty();
        }
    }

    /**
     * Строит дерево от корня к терминалам.
     *
     * @param rootSpareDegree сколько участков новой сети можно привести в корень.
     *                        Для врезки в существующую камеру это предел раздела 3 ТП
     *                        минус уже примыкающие участки; для новой камеры на участке —
     *                        предел минус две половины разрезанного участка.
     * @param seedTerminal    терминал, подключаемый первым; {@code null} — обычный порядок
     *                        «ближайший к дереву». Затравка меняет форму дерева и служит
     *                        источником содержательно разных вариантов.
     */
    public Result build(RoutingGraph graph, int rootNode, List<Terminal> terminals,
                        int rootSpareDegree, Terminal seedTerminal) {
        RouteTree tree = new RouteTree(rootNode);
        List<Terminal> remaining = new ArrayList<>(terminals);
        List<String> unreachable = new ArrayList<>();
        int maxDegree = catalog.props().getMaxChamberDegree();

        // Точка подключения — всегда конец ветви. Транзит через неё означал бы, что
        // труба заходит в чужое здание к его ИТП и выходит обратно, а развилка при этом
        // оказывается не в тепловой камере, как требует раздел 3 ТП.
        Set<Integer> terminalNodes = new LinkedHashSet<>();
        terminals.forEach(t -> terminalNodes.add(t.getNodeIndex()));

        if (seedTerminal != null && remaining.remove(seedTerminal)) {
            if (!attachNearest(graph, tree, List.of(seedTerminal), rootNode,
                    rootSpareDegree, maxDegree, terminalNodes)) {
                unreachable.add(seedTerminal.getOksId());
            }
        }

        while (!remaining.isEmpty()) {
            Terminal connected = attachNearestTerminal(graph, tree, remaining, rootNode,
                    rootSpareDegree, maxDegree, terminalNodes);
            if (connected == null) {
                // Оставшиеся терминалы недостижимы из дерева. Это не повод бросать
                // расчёт: раздел 2.9 ТЗ требует сохранить построенную часть результата
                // и отдельно перечислить ОКС без маршрута.
                remaining.forEach(t -> unreachable.add(t.getOksId()));
                break;
            }
            remaining.remove(connected);
        }

        return new Result(tree, unreachable);
    }

    private boolean attachNearest(RoutingGraph graph, RouteTree tree, List<Terminal> candidates,
                                  int rootNode, int rootSpareDegree, int maxDegree,
                                  Set<Integer> terminalNodes) {
        return attachNearestTerminal(graph, tree, candidates, rootNode,
                rootSpareDegree, maxDegree, terminalNodes) != null;
    }

    /**
     * Присоединяет к дереву ближайший из кандидатов и возвращает его.
     * Источниками поиска служат только узлы дерева с запасом по числу примыканий;
     * узлы без запаса дополнительно запрещены для транзита.
     */
    private Terminal attachNearestTerminal(RoutingGraph graph, RouteTree tree,
                                           List<Terminal> candidates, int rootNode,
                                           int rootSpareDegree, int maxDegree,
                                           Set<Integer> terminalNodes) {
        Set<Integer> sources = new LinkedHashSet<>();
        Set<Integer> blocked = new LinkedHashSet<>();

        for (int node : tree.nodes()) {
            if (terminalNodes.contains(node)) {
                // Уже подключённый ОКС новых ветвей от себя не даёт.
                continue;
            }
            if (hasSpareDegree(tree, node, rootNode, rootSpareDegree, maxDegree)) {
                sources.add(node);
            } else {
                blocked.add(node);
            }
        }
        if (sources.isEmpty()) {
            return null;
        }

        RoutingGraph.Frontier frontier = graph.dijkstra(sources, blocked, terminalNodes);

        Terminal best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (Terminal t : candidates) {
            double d = frontier.getDist()[t.getNodeIndex()];
            if (d < bestDist) {
                bestDist = d;
                best = t;
            }
        }
        if (best == null || Double.isInfinite(bestDist)) {
            return null;
        }

        tree.attachPath(frontier.pathTo(best.getNodeIndex()));
        tree.markTerminal(best.getNodeIndex(), best.getOksId(), best.getFlowTph());
        return best;
    }

    /**
     * Есть ли у узла запас по числу примыкающих участков.
     * <p>
     * Корень — это точка врезки: часть допустимых примыканий у неё уже занята
     * существующей сетью. Прочие узлы дерева имеют одного родителя, поэтому
     * им остаётся предел минус один.
     */
    private boolean hasSpareDegree(RouteTree tree, int node, int rootNode,
                                   int rootSpareDegree, int maxDegree) {
        int used = tree.childrenOf(node).size();
        if (node == rootNode) {
            return used < rootSpareDegree;
        }
        return used < maxDegree - 1;
    }
}
