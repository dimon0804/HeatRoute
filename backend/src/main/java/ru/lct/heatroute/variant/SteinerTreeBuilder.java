package ru.lct.heatroute.variant;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.routing.RoutingGraph;

import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * Проверка проходимости прямого отрезка между двумя точками.
     * Нужна при врезке новой камеры в уже построенный участок: такого ребра
     * в графе видимости нет, и его клиренс приходится проверять отдельно.
     */
    @FunctionalInterface
    public interface Passability {
        boolean check(Coordinate from, Coordinate to, String exemptOksId);
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
        return build(graph, rootNode, terminals, rootSpareDegree, seedTerminal,
                (a, b, oks) -> true, null);
    }

    /**
     * @param passable проверка клиренса для ветви, отходящей от новой камеры
     *                 на уже построенном участке
     * @param allowedEdges маска разрешённых рёбер графа от {@link RouteBarrier}:
     *                     принятые части сети и зоны, где манёвр по глубине
     *                     не помещается. В отличие от {@code passable} действует
     *                     на основной поиск маршрута, а не только на отводы.
     *                     {@code null} — запрещать нечего
     */
    public Result build(RoutingGraph graph, int rootNode, List<Terminal> terminals,
                        int rootSpareDegree, Terminal seedTerminal, Passability passable,
                        boolean[] allowedEdges) {
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
                    rootSpareDegree, maxDegree, terminalNodes, allowedEdges)
                    && !attachThroughNewChamber(graph, tree, seedTerminal, maxDegree, passable)) {
                log.debug("Затравочный терминал {} не присоединился к узлу {}",
                        seedTerminal.getOksId(), rootNode);
                unreachable.add(seedTerminal.getOksId());
            }
        }

        while (!remaining.isEmpty()) {
            Terminal connected = attachNearestTerminal(graph, tree, remaining, rootNode,
                    rootSpareDegree, maxDegree, terminalNodes, allowedEdges);

            if (connected == null) {
                // Узлы дерева исчерпали предел примыканий. Физически это не тупик:
                // инженер ставит тепловую камеру на уже проложенном участке и ветвится
                // от неё. Делаем то же самое — врезаем узел в ближайший участок дерева.
                connected = attachNearestThroughNewChamber(graph, tree, remaining,
                        maxDegree, passable);
            }

            if (connected == null) {
                // Оставшиеся терминалы недостижимы из дерева. Это не повод бросать
                // расчёт: раздел 2.9 ТЗ требует сохранить построенную часть результата
                // и отдельно перечислить ОКС без маршрута.
                log.debug("Не удалось присоединить к дереву от узла {}: {} "
                                + "(в дереве узлов {}, подключено ОКС {})",
                        rootNode,
                        remaining.stream().map(Terminal::getOksId)
                                .collect(java.util.stream.Collectors.toList()),
                        tree.nodes().size(), tree.getTerminalOks().size());
                remaining.forEach(t -> unreachable.add(t.getOksId()));
                break;
            }
            remaining.remove(connected);
        }

        return new Result(tree, unreachable);
    }

    private boolean attachNearest(RoutingGraph graph, RouteTree tree, List<Terminal> candidates,
                                  int rootNode, int rootSpareDegree, int maxDegree,
                                  Set<Integer> terminalNodes, boolean[] allowedEdges) {
        return attachNearestTerminal(graph, tree, candidates, rootNode,
                rootSpareDegree, maxDegree, terminalNodes, allowedEdges) != null;
    }

    /**
     * Присоединяет к дереву ближайший из кандидатов и возвращает его.
     * Источниками поиска служат только узлы дерева с запасом по числу примыканий;
     * узлы без запаса дополнительно запрещены для транзита.
     */
    private Terminal attachNearestTerminal(RoutingGraph graph, RouteTree tree,
                                           List<Terminal> candidates, int rootNode,
                                           int rootSpareDegree, int maxDegree,
                                           Set<Integer> terminalNodes,
                                           boolean[] allowedEdges) {
        Set<Integer> sources = new LinkedHashSet<>();
        Set<Integer> blocked = new LinkedHashSet<>();

        for (int node : tree.nodes()) {
            if (terminalNodes.contains(node)) {
                // Уже подключённый ОКС новых ветвей от себя не даёт.
                continue;
            }
            if (tree.isSynthetic(node)) {
                // Камеры, врезанные в участок дерева, в графе видимости отсутствуют:
                // их индексы лежат вне его диапазона. Ветвиться от них можно только
                // повторной врезкой, а не поиском пути по графу.
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

        RoutingGraph.Frontier frontier =
                graph.dijkstra(sources, blocked, terminalNodes, allowedEdges);

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
     * Присоединяет ближайший из оставшихся терминалов через новую камеру
     * на уже построенном участке дерева.
     */
    private Terminal attachNearestThroughNewChamber(RoutingGraph graph, RouteTree tree,
                                                    List<Terminal> candidates, int maxDegree,
                                                    Passability passable) {
        Terminal best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Terminal terminal : candidates) {
            Coordinate location = graph.node(terminal.getNodeIndex()).getLocation();
            double distance = nearestEdgeDistance(graph, tree, location);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = terminal;
            }
        }
        if (best == null || Double.isInfinite(bestDistance)) {
            return null;
        }
        return attachThroughNewChamber(graph, tree, best, maxDegree, passable) ? best : null;
    }

    /**
     * Врезает узел в участок дерева, ближайший к терминалу, и отводит от него ветвь.
     * <p>
     * Узел ставится в проекции терминала на участок — это кратчайший отвод. Обе части
     * разрезанного участка лежат внутри исходного и заведомо проходимы; отвод от узла
     * к терминалу — новый отрезок, и его клиренс проверяется отдельно.
     */
    private boolean attachThroughNewChamber(RoutingGraph graph, RouteTree tree,
                                            Terminal terminal, int maxDegree,
                                            Passability passable) {
        Coordinate target = graph.node(terminal.getNodeIndex()).getLocation();

        List<int[]> edges = new ArrayList<>(tree.edges());
        edges.sort(Comparator.comparingDouble(e -> distanceToEdge(graph, tree, e, target)));

        for (int[] edge : edges) {
            Coordinate a = tree.locationOf(graph, edge[0]);
            Coordinate b = tree.locationOf(graph, edge[1]);
            Coordinate projection = project(a, b, target);

            // Проекция легла на конец участка — это обычное присоединение к узлу,
            // и оно уже не прошло по пределу примыканий.
            if (projection.distance(a) < 1.0 || projection.distance(b) < 1.0) {
                continue;
            }
            if (!passable.check(projection, target, terminal.getOksId())) {
                continue;
            }

            // Новый узел получит три примыкания: две части разрезанного участка
            // и отвод к терминалу.
            if (3 > maxDegree) {
                return false;
            }
            int chamber = tree.addSyntheticNode(projection);
            tree.reparent(chamber, edge[0]);
            tree.reparent(edge[1], chamber);
            tree.reparent(terminal.getNodeIndex(), chamber);
            tree.markTerminal(terminal.getNodeIndex(), terminal.getOksId(), terminal.getFlowTph());
            log.debug("ОКС {} присоединён через новую камеру на участке дерева",
                    terminal.getOksId());
            return true;
        }
        return false;
    }

    private double nearestEdgeDistance(RoutingGraph graph, RouteTree tree, Coordinate target) {
        return tree.edges().stream()
                .mapToDouble(e -> distanceToEdge(graph, tree, e, target))
                .min().orElse(Double.POSITIVE_INFINITY);
    }

    private double distanceToEdge(RoutingGraph graph, RouteTree tree, int[] edge,
                                  Coordinate target) {
        return project(tree.locationOf(graph, edge[0]), tree.locationOf(graph, edge[1]), target)
                .distance(target);
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
