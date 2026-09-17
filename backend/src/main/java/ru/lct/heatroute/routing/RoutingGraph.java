package ru.lct.heatroute.routing;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Граф видимости по буферизованным препятствиям.
 * <p>
 * Ребро существует между двумя узлами, если прямой отрезок между ними не заходит
 * внутрь запретной зоны. Кратчайший путь в таком графе состоит из прямых отрезков
 * и огибает препятствия по касательной — это ровно та геометрия, которую рисует
 * инженер, и ровно то, что требует критерий «разумность маршрутов, количество
 * поворотов, отсутствие случайной мелкой ломаной». Сеточный поиск при любом шаге
 * даёт ступенчатую линию, которую потом приходится сглаживать, рискуя нарушить
 * клиренсы.
 * <p>
 * Вес ребра — приведённая длина: геометрическая длина, умноженная на коэффициенты
 * специальных проходов. Стоимость метра зависит от условного диаметра одинаково вдоль
 * всего пути с одним расходом, поэтому минимум приведённой длины совпадает с минимумом
 * стоимости при любом фиксированном ДУ. Так поиск пути отделён от подбора диаметра.
 */
@Slf4j
public class RoutingGraph {

    /** Узел графа. */
    @Getter
    public static class Node {
        private final int index;
        private final Coordinate location;
        /**
         * ID перспективного ОКС, если узел — его точка подключения. Собственный контур
         * такого ОКС не препятствует рёбрам, инцидентным этому узлу: труба заходит
         * в здание к своему ИТП.
         */
        private final String terminalOksId;
        /** Внешний идентификатор (точка подключения, кандидат врезки) или {@code null}. */
        private final String externalId;
        private final NodeKind kind;

        Node(int index, Coordinate location, NodeKind kind, String terminalOksId, String externalId) {
            this.index = index;
            this.location = location;
            this.kind = kind;
            this.terminalOksId = terminalOksId;
            this.externalId = externalId;
        }
    }

    public enum NodeKind {
        /** Вершина контура препятствия — опора для огибания. */
        OBSTACLE_VERTEX,
        /** Точка подключения перспективного ОКС. */
        TERMINAL,
        /** Кандидат точки врезки в существующую сеть. */
        TIE_IN_CANDIDATE
    }

    private final List<Node> nodes;
    private final int dn;

    /** Разреженное представление смежности: сжатые строки. */
    private int[] edgeStart;
    private int[] edgeTarget;
    private double[] edgeWeight;
    private double[] edgeLength;

    private final STRtree nodeIndex = new STRtree();

    private RoutingGraph(List<Node> nodes, int dn) {
        this.nodes = nodes;
        this.dn = dn;
    }

    public List<Node> nodes() {
        return nodes;
    }

    public int size() {
        return nodes.size();
    }

    public Node node(int index) {
        return nodes.get(index);
    }

    public int edgeCount() {
        return edgeTarget == null ? 0 : edgeTarget.length;
    }

    // =================================================================================
    //  Построение
    // =================================================================================

    /**
     * Строит граф из вершин препятствий и заданных узлов-терминалов.
     *
     * @param field  поле препятствий
     * @param dn     условный диаметр, по которому вычислены клиренсы
     * @param extras терминалы и кандидаты врезки
     */
    public static RoutingGraph build(ObstacleField field, int dn, List<Node> extras,
                                     RoutingProperties props) {
        long started = System.nanoTime();

        List<Node> nodes = new ArrayList<>();
        for (Coordinate c : field.visibilityVertices(dn)) {
            nodes.add(new Node(nodes.size(), c, NodeKind.OBSTACLE_VERTEX, null, null));
        }
        for (Node extra : extras) {
            nodes.add(new Node(nodes.size(), extra.getLocation(), extra.getKind(),
                    extra.getTerminalOksId(), extra.getExternalId()));
        }

        // Слой препятствий готовится до параллельной фазы: внутри неё ленивая
        // инициализация подготовленных геометрий недопустима.
        field.prepare(dn);

        RoutingGraph graph = new RoutingGraph(nodes, dn);
        graph.indexNodes();
        graph.connect(field, props);

        log.info("Граф видимости для ДУ {}: узлов {}, рёбер {}, {} мс",
                dn, graph.size(), graph.edgeCount() / 2,
                (System.nanoTime() - started) / 1_000_000);
        return graph;
    }

    private void indexNodes() {
        for (Node n : nodes) {
            Coordinate c = n.getLocation();
            nodeIndex.insert(new Envelope(c.x, c.x, c.y, c.y), n);
        }
        nodeIndex.build();
    }

    @SuppressWarnings("unchecked")
    private void connect(ObstacleField field, RoutingProperties props) {
        double obstacleRadius = props.getVisibilityRadius();
        double terminalRadius = Math.max(obstacleRadius, props.getTerminalVisibilityRadius());
        int n = nodes.size();

        List<int[]> targets = new ArrayList<>(n);
        List<double[]> weights = new ArrayList<>(n);
        List<double[]> lengths = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            targets.add(null);
            weights.add(null);
            lengths.add(null);
        }

        List<List<int[]>> pending = new ArrayList<>(n);
        List<List<double[]>> pendingW = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            pending.add(new ArrayList<>());
            pendingW.add(new ArrayList<>());
        }

        java.util.concurrent.atomic.LongAdder checks = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.LongAdder accepted = new java.util.concurrent.atomic.LongAdder();

        // Проверка пары узлов независима от остальных, а пар сотни тысяч: работа
        // раскладывается по ядрам. Каждый поток пишет только в свой список, поэтому
        // общей блокировки не требуется.
        List<List<long[]>> forward = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            forward.add(new ArrayList<>());
        }

        java.util.stream.IntStream.range(0, n).parallel().forEach(i -> {
            Node a = nodes.get(i);
            Coordinate ca = a.getLocation();
            boolean aWide = a.getKind() != NodeKind.OBSTACLE_VERTEX;
            double queryRadius = aWide ? terminalRadius : obstacleRadius;
            Envelope env = new Envelope(ca.x - queryRadius, ca.x + queryRadius,
                    ca.y - queryRadius, ca.y + queryRadius);
            List<Node> near = nodeIndex.query(env);

            for (Node b : near) {
                int j = b.getIndex();
                if (j <= i) {
                    continue;   // ребро строится один раз, хранится в обе стороны
                }
                Coordinate cb = b.getLocation();
                double length = ca.distance(cb);
                // Полный обзор действует, если хотя бы один конец — терминал
                // или кандидат врезки; между вершинами препятствий радиус короче.
                double limit = aWide || b.getKind() != NodeKind.OBSTACLE_VERTEX
                        ? terminalRadius : obstacleRadius;
                if (length <= 0 || length > limit) {
                    continue;
                }
                checks.increment();

                org.locationtech.jts.geom.LineString probe = field.probe(ca, cb);
                if (probe == null) {
                    continue;
                }
                // Собственные контуры обоих концов не мешают ребру между ними.
                if (!passable(field, probe, a, b)) {
                    continue;
                }
                if (!field.crossingAngleOk(probe, dn)) {
                    continue;
                }
                double weight = length * field.crossingCostFactor(probe, dn);
                accepted.increment();

                forward.get(i).add(new long[]{j, Double.doubleToLongBits(weight),
                        Double.doubleToLongBits(length)});
            }
        });

        // Сборка двусторонней смежности из односторонних списков.
        for (int i = 0; i < n; i++) {
            for (long[] e : forward.get(i)) {
                int j = (int) e[0];
                double weight = Double.longBitsToDouble(e[1]);
                double length = Double.longBitsToDouble(e[2]);
                pending.get(i).add(new int[]{j});
                pendingW.get(i).add(new double[]{weight, length});
                pending.get(j).add(new int[]{i});
                pendingW.get(j).add(new double[]{weight, length});
            }
        }

        int total = 0;
        for (List<int[]> list : pending) {
            total += list.size();
        }
        edgeStart = new int[n + 1];
        edgeTarget = new int[total];
        edgeWeight = new double[total];
        edgeLength = new double[total];

        int cursor = 0;
        for (int i = 0; i < n; i++) {
            edgeStart[i] = cursor;
            List<int[]> t = pending.get(i);
            List<double[]> w = pendingW.get(i);
            for (int k = 0; k < t.size(); k++) {
                edgeTarget[cursor] = t.get(k)[0];
                edgeWeight[cursor] = w.get(k)[0];
                edgeLength[cursor] = w.get(k)[1];
                cursor++;
            }
        }
        edgeStart[n] = cursor;

        log.debug("Проверено пар узлов: {}, принято рёбер: {} ({}%)",
                checks.sum(), accepted.sum(),
                checks.sum() == 0 ? 0 : accepted.sum() * 100 / checks.sum());
    }

    private boolean passable(ObstacleField field,
                             org.locationtech.jts.geom.LineString probe, Node a, Node b) {
        String exemptA = a.getTerminalOksId();
        String exemptB = b.getTerminalOksId();
        if (exemptA == null && exemptB == null) {
            return field.blockingObstacle(probe, dn, null) == null;
        }
        if (exemptA != null && exemptB != null && !exemptA.equals(exemptB)) {
            // Оба конца — терминалы разных ОКС: исключение действует для обоих контуров.
            ObstacleField.Obstacle blocker = field.blockingObstacle(probe, dn, exemptA);
            return blocker == null || blocker.isOwnedBy(exemptB);
        }
        return field.blockingObstacle(probe, dn, exemptA != null ? exemptA : exemptB) == null;
    }

    // =================================================================================
    //  Поиск пути
    // =================================================================================

    /** Результат поиска: последовательность узлов, приведённая и геометрическая длина. */
    @Getter
    public static class Path {
        private final int[] nodes;
        private final double weight;
        private final double length;

        Path(int[] nodes, double weight, double length) {
            this.nodes = nodes;
            this.weight = weight;
            this.length = length;
        }

        public boolean found() {
            return nodes.length > 0;
        }
    }

    private static final Path NOT_FOUND = new Path(new int[0], Double.POSITIVE_INFINITY, 0);

    /**
     * Кратчайший путь по приведённой длине, A\* с эвристикой по прямой.
     * Эвристика допустима: коэффициенты специальных проходов не меньше единицы,
     * поэтому приведённая длина никогда не меньше геометрической.
     */
    public Path shortestPath(int from, int to) {
        if (from == to) {
            return new Path(new int[]{from}, 0, 0);
        }
        int n = nodes.size();
        double[] dist = new double[n];
        double[] len = new double[n];
        int[] prev = new int[n];
        boolean[] done = new boolean[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        dist[from] = 0;

        Coordinate target = nodes.get(to).getLocation();
        PriorityQueue<long[]> queue = new PriorityQueue<>((x, y) ->
                Double.compare(Double.longBitsToDouble(x[0]), Double.longBitsToDouble(y[0])));
        queue.add(new long[]{Double.doubleToLongBits(heuristic(from, target)), from});

        while (!queue.isEmpty()) {
            long[] head = queue.poll();
            int u = (int) head[1];
            if (done[u]) {
                continue;
            }
            done[u] = true;
            if (u == to) {
                break;
            }
            for (int e = edgeStart[u]; e < edgeStart[u + 1]; e++) {
                int v = edgeTarget[e];
                if (done[v]) {
                    continue;
                }
                double nd = dist[u] + edgeWeight[e];
                if (nd < dist[v]) {
                    dist[v] = nd;
                    len[v] = len[u] + edgeLength[e];
                    prev[v] = u;
                    queue.add(new long[]{
                            Double.doubleToLongBits(nd + heuristic(v, target)), v});
                }
            }
        }

        if (!done[to] || Double.isInfinite(dist[to])) {
            return NOT_FOUND;
        }
        return new Path(reconstruct(prev, from, to), dist[to], len[to]);
    }

    /**
     * Расстояния по приведённой длине от узла до всех остальных (Дейкстра).
     * Нужны для кластеризации ОКС и для выбора точки врезки: там сравниваются
     * расстояния до множества целей сразу, и отдельный A\* на каждую цель избыточен.
     */
    public double[] distancesFrom(int from) {
        int n = nodes.size();
        double[] dist = new double[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        dist[from] = 0;
        boolean[] done = new boolean[n];

        PriorityQueue<long[]> queue = new PriorityQueue<>((x, y) ->
                Double.compare(Double.longBitsToDouble(x[0]), Double.longBitsToDouble(y[0])));
        queue.add(new long[]{Double.doubleToLongBits(0), from});

        while (!queue.isEmpty()) {
            long[] head = queue.poll();
            int u = (int) head[1];
            if (done[u]) {
                continue;
            }
            done[u] = true;
            for (int e = edgeStart[u]; e < edgeStart[u + 1]; e++) {
                int v = edgeTarget[e];
                double nd = dist[u] + edgeWeight[e];
                if (nd < dist[v]) {
                    dist[v] = nd;
                    queue.add(new long[]{Double.doubleToLongBits(nd), v});
                }
            }
        }
        return dist;
    }

    /**
     * Результат многоисточникового обхода: расстояние до каждого узла и предшественник
     * на кратчайшем пути. Нужен эвристике Штейнера: на каждой итерации ищется терминал,
     * ближайший не к одной точке, а ко всему уже построенному дереву.
     */
    @Getter
    public static class Frontier {
        private final double[] dist;
        private final double[] length;
        private final int[] prev;

        Frontier(double[] dist, double[] length, int[] prev) {
            this.dist = dist;
            this.length = length;
            this.prev = prev;
        }

        /** Путь от найденного узла назад к ближайшему источнику, включая оба конца. */
        public int[] pathTo(int node) {
            if (Double.isInfinite(dist[node])) {
                return new int[0];
            }
            int count = 1;
            for (int at = node; prev[at] >= 0; at = prev[at]) {
                count++;
            }
            int[] path = new int[count];
            int at = node;
            for (int i = count - 1; i >= 0; i--) {
                path[i] = at;
                if (prev[at] >= 0) {
                    at = prev[at];
                }
            }
            return path;
        }
    }

    /**
     * Дейкстра сразу от множества источников: все они получают нулевое расстояние.
     * Один проход по графу вместо отдельного поиска от каждого узла дерева.
     */
    public Frontier dijkstra(java.util.Collection<Integer> sources) {
        return dijkstra(sources, java.util.Set.of());
    }

    /**
     * То же, но часть узлов исключена из обхода. Нужно для ограничения на число
     * участков, примыкающих к тепловой камере: узел, исчерпавший предел примыканий,
     * не может ни принять новую ветвь, ни быть транзитным для неё.
     */
    public Frontier dijkstra(java.util.Collection<Integer> sources,
                             java.util.Set<Integer> blocked) {
        return dijkstra(sources, blocked, java.util.Set.of());
    }

    /**
     * Обход с двумя видами ограничений.
     *
     * @param blocked   узлы, недоступные вовсе
     * @param noTransit узлы, до которых дойти можно, но пройти насквозь нельзя.
     *                  Так ведут себя точки подключения ОКС: трасса в них заканчивается,
     *                  а не проходит через здание соседнего объекта транзитом.
     */
    public Frontier dijkstra(java.util.Collection<Integer> sources,
                             java.util.Set<Integer> blocked,
                             java.util.Set<Integer> noTransit) {
        int n = nodes.size();
        double[] dist = new double[n];
        double[] len = new double[n];
        int[] prev = new int[n];
        boolean[] done = new boolean[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);

        PriorityQueue<long[]> queue = new PriorityQueue<>((x, y) ->
                Double.compare(Double.longBitsToDouble(x[0]), Double.longBitsToDouble(y[0])));
        for (int s : sources) {
            if (blocked.contains(s)) {
                continue;
            }
            dist[s] = 0;
            queue.add(new long[]{Double.doubleToLongBits(0), s});
        }

        while (!queue.isEmpty()) {
            long[] head = queue.poll();
            int u = (int) head[1];
            if (done[u]) {
                continue;
            }
            done[u] = true;
            if (noTransit.contains(u) && dist[u] > 0) {
                // Узел достигнут, но дальше через него не идём.
                continue;
            }
            for (int e = edgeStart[u]; e < edgeStart[u + 1]; e++) {
                int v = edgeTarget[e];
                if (blocked.contains(v)) {
                    continue;
                }
                double nd = dist[u] + edgeWeight[e];
                if (nd < dist[v]) {
                    dist[v] = nd;
                    len[v] = len[u] + edgeLength[e];
                    prev[v] = u;
                    queue.add(new long[]{Double.doubleToLongBits(nd), v});
                }
            }
        }
        return new Frontier(dist, len, prev);
    }

    /** Число рёбер, инцидентных узлу графа. Нужно для диагностики изоляции узлов. */
    public int degreeOf(int node) {
        return edgeStart[node + 1] - edgeStart[node];
    }

    /** Геометрическая длина ребра между соседними узлами; {@code NaN}, если ребра нет. */
    public double edgeLength(int from, int to) {
        for (int e = edgeStart[from]; e < edgeStart[from + 1]; e++) {
            if (edgeTarget[e] == to) {
                return edgeLength[e];
            }
        }
        return Double.NaN;
    }

    /** Координаты пути для построения геометрии участка. */
    public List<Coordinate> coordinates(Path path) {
        return coordinates(path.getNodes());
    }

    public List<Coordinate> coordinates(int[] path) {
        List<Coordinate> out = new ArrayList<>(path.length);
        for (int index : path) {
            out.add(nodes.get(index).getLocation());
        }
        return out;
    }

    private double heuristic(int node, Coordinate target) {
        return nodes.get(node).getLocation().distance(target);
    }

    private int[] reconstruct(int[] prev, int from, int to) {
        int count = 1;
        for (int at = to; at != from; at = prev[at]) {
            count++;
        }
        int[] path = new int[count];
        int at = to;
        for (int i = count - 1; i >= 0; i--) {
            path[i] = at;
            if (at != from) {
                at = prev[at];
            }
        }
        return path;
    }

    /** Фабрика узла-терминала для передачи в {@link #build}. */
    public static Node terminal(Coordinate c, String oksId) {
        return new Node(-1, c, NodeKind.TERMINAL, oksId, oksId);
    }

    /** Фабрика узла-кандидата врезки. */
    public static Node tieInCandidate(Coordinate c, String externalId) {
        return new Node(-1, c, NodeKind.TIE_IN_CANDIDATE, null, externalId);
    }
}
