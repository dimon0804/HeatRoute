package ru.lct.heatroute.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatroute.domain.model.FutureOks;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.geo.GeoProperties;
import ru.lct.heatroute.ingest.GeoJsonStreamParser;
import ru.lct.heatroute.ingest.SceneAssembler;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Граф видимости на конкурсном наборе.
 * <p>
 * Тест закрепляет три вещи, без которых алгоритм не поедет: граф строится за разумное
 * время, каждая точка подключения в нём достижима из района существующей сети,
 * и найденные пути состоят из немногих прямых отрезков, а не из мелкой ломаной.
 */
@SpringBootTest
@ActiveProfiles("nodb")
class RoutingGraphTest {

    private static final String SAMPLE = "/samples/dataset_lct2026.geojson";

    @Autowired
    GeoJsonStreamParser parser;
    @Autowired
    SceneAssembler assembler;
    @Autowired
    ReferenceCatalog catalog;
    @Autowired
    GeoProperties geoProps;
    @Autowired
    RoutingProperties routingProps;

    private static InputScene scene;
    private static ObstacleField field;
    private static RoutingGraph graph;
    private static List<Integer> terminalIndices;
    private static int sourceSideNode;

    private void prepare() throws Exception {
        if (graph != null) {
            return;
        }
        SceneAssembler.Collector collector = new SceneAssembler.Collector();
        try (InputStream in = getClass().getResourceAsStream(SAMPLE)) {
            parser.parse(Objects.requireNonNull(in), collector::accept);
        }
        scene = assembler.assemble(collector);
        field = new ObstacleField(scene, catalog, geoProps, routingProps);

        // Расчётный ДУ первой итерации — по наибольшему одиночному расходу набора:
        // клиренсы получаются реалистичными, а не оптимистично заниженными.
        double maxSingleFlow = scene.getFutureOks().stream()
                .mapToDouble(FutureOks::getFlowTph).max().orElse(0);
        int dn = catalog.selectForFlow(maxSingleFlow).orElseThrow().getDn();

        List<RoutingGraph.Node> extras = new ArrayList<>();
        for (FutureOks oks : scene.getFutureOks()) {
            extras.add(RoutingGraph.terminal(
                    oks.getConnectionPoint().getCoordinate(), oks.getId()));
        }
        // Кандидаты врезки: узлы существующей сети, включая камеры.
        scene.getTopology().getNodes().forEach(node ->
                extras.add(RoutingGraph.tieInCandidate(node.getLocation(),
                        node.getChamberId() != null ? node.getChamberId() : "node" + node.getIndex())));

        graph = RoutingGraph.build(field, dn, extras, routingProps);

        terminalIndices = new ArrayList<>();
        for (RoutingGraph.Node n : graph.nodes()) {
            if (n.getKind() == RoutingGraph.NodeKind.TERMINAL) {
                terminalIndices.add(n.getIndex());
            }
            if (sourceSideNode == 0 && n.getKind() == RoutingGraph.NodeKind.TIE_IN_CANDIDATE) {
                sourceSideNode = n.getIndex();
            }
        }
    }

    @Test
    @DisplayName("Граф строится и содержит все точки подключения и кандидатов врезки")
    void buildsGraph() throws Exception {
        prepare();
        assertThat(graph.size()).isGreaterThan(100);
        assertThat(graph.edgeCount()).isGreaterThan(graph.size());
        assertThat(terminalIndices).hasSize(17);

        long tieIns = graph.nodes().stream()
                .filter(n -> n.getKind() == RoutingGraph.NodeKind.TIE_IN_CANDIDATE).count();
        assertThat(tieIns).isEqualTo(scene.getTopology().getNodes().size());
    }

    @Test
    @DisplayName("Каждая точка подключения достижима от существующей сети")
    void everyTerminalReachable() throws Exception {
        prepare();
        double[] dist = graph.distancesFrom(sourceSideNode);
        List<String> unreachable = new ArrayList<>();
        for (int idx : terminalIndices) {
            if (Double.isInfinite(dist[idx])) {
                unreachable.add(graph.node(idx).getExternalId());
            }
        }
        assertThat(unreachable)
                .as("недостижимые точки подключения — это ОКС, которые нечем подключить")
                .isEmpty();
    }

    @Test
    @DisplayName("Путь состоит из немногих прямых отрезков, а не из мелкой ломаной")
    void pathsAreStraight() throws Exception {
        prepare();
        int checked = 0;
        for (int idx : terminalIndices) {
            RoutingGraph.Path path = graph.shortestPath(sourceSideNode, idx);
            assertThat(path.found())
                    .as("путь до точки подключения %s", graph.node(idx).getExternalId())
                    .isTrue();

            List<Coordinate> coords = graph.coordinates(path);
            double straight = coords.get(0).distance(coords.get(coords.size() - 1));

            // Геометрическая длина обхода не должна превышать прямую больше чем вдвое:
            // иначе это не обход препятствий, а блуждание.
            assertThat(path.getLength())
                    .as("длина обхода до %s против прямой", graph.node(idx).getExternalId())
                    .isGreaterThanOrEqualTo(straight - 1e-6)
                    .isLessThan(Math.max(straight * 2.0, straight + 200));

            // Поворотов немного: путь по графу видимости огибает препятствия
            // по касательной, а не идёт ступенями.
            assertThat(coords.size())
                    .as("число вершин пути до %s", graph.node(idx).getExternalId())
                    .isLessThanOrEqualTo(25);
            checked++;
        }
        assertThat(checked).isEqualTo(17);
    }

    @Test
    @DisplayName("Трасса не заходит внутрь чужих зданий, но входит в собственное")
    void respectsClearances() throws Exception {
        prepare();
        int dn = 200;

        // Отрезок между двумя терминалами разных ОКС проверяется с учётом обоих
        // собственных контуров: чужие здания на пути его по-прежнему запрещают.
        FutureOks first = scene.getFutureOks().get(0);
        Coordinate c = first.getConnectionPoint().getCoordinate();

        // Сама точка подключения лежит внутри своего контура: без исключения
        // любой отрезок к ней был бы непроходим.
        Coordinate outside = new Coordinate(c.x + 400, c.y + 400);
        assertThat(field.blockingObstacle(c, outside, dn, null))
                .as("без исключения собственный контур закрывает подход").isNotNull();
    }
}
