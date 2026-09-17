package ru.lct.heatroute.variant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatroute.domain.model.FutureOks;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.geo.GeoProperties;
import ru.lct.heatroute.ingest.GeoJsonStreamParser;
import ru.lct.heatroute.ingest.SceneAssembler;
import ru.lct.heatroute.routing.ObstacleField;
import ru.lct.heatroute.routing.RoutingGraph;
import ru.lct.heatroute.routing.RoutingProperties;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Диагностика достижимости точек подключения в графе маршрутизации.
 * <p>
 * Отделяет геометрическую недостижимость от последствий ограничений построения:
 * сначала проверяется чистый граф, затем граф с запретом транзита через чужие точки
 * подключения. Если терминал теряется на втором шаге, проблема в конструкции дерева,
 * а не в исходных данных.
 */
@SpringBootTest
@ActiveProfiles("nodb")
class ReachabilityDiagnosticTest {

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
    @Autowired
    TieInCandidateFinder tieInFinder;

    @Test
    @DisplayName("Каждая точка подключения достижима и без транзита через чужие терминалы")
    void terminalsReachableWithoutTerminalTransit() throws Exception {
        SceneAssembler.Collector collector = new SceneAssembler.Collector();
        try (InputStream in = getClass().getResourceAsStream("/samples/dataset_lct2026.geojson")) {
            parser.parse(Objects.requireNonNull(in), collector::accept);
        }
        InputScene scene = assembler.assemble(collector);

        int dn = catalog.selectForFlow(scene.totalFutureFlowTph()).orElseThrow().getDn();
        ObstacleField field = new ObstacleField(scene, catalog, geoProps, routingProps);

        List<TieInCandidate> candidates = tieInFinder.find(scene);
        List<RoutingGraph.Node> extras = new ArrayList<>();
        for (FutureOks oks : scene.getFutureOks()) {
            extras.add(RoutingGraph.terminal(oks.getConnectionPoint().getCoordinate(), oks.getId()));
        }
        for (TieInCandidate c : candidates) {
            extras.add(RoutingGraph.tieInCandidate(c.getLocation(), c.getId()));
        }
        RoutingGraph graph = RoutingGraph.build(field, dn, extras, routingProps);

        Map<String, Integer> byExternal = new LinkedHashMap<>();
        for (RoutingGraph.Node n : graph.nodes()) {
            if (n.getExternalId() != null) {
                byExternal.putIfAbsent(n.getExternalId(), n.getIndex());
            }
        }
        Set<Integer> terminalNodes = new LinkedHashSet<>();
        for (FutureOks oks : scene.getFutureOks()) {
            terminalNodes.add(byExternal.get(oks.getId()));
        }

        int root = candidates.stream()
                .filter(TieInCandidate::isUsesExistingChamber)
                .map(c -> byExternal.get(c.getId()))
                .filter(Objects::nonNull)
                .findFirst().orElseThrow();

        RoutingGraph.Frontier plain = graph.dijkstra(Set.of(root));
        RoutingGraph.Frontier noTransit = graph.dijkstra(Set.of(root), Set.of(), terminalNodes);

        System.out.println();
        System.out.println("=========== ДОСТИЖИМОСТЬ ТОЧЕК ПОДКЛЮЧЕНИЯ ===========");
        System.out.printf("%-6s %14s %14s %8s%n", "ОКС", "обычный граф", "без транзита", "степень");
        List<String> lostWithoutTransit = new ArrayList<>();
        for (FutureOks oks : scene.getFutureOks()) {
            int idx = byExternal.get(oks.getId());
            double a = plain.getDist()[idx];
            double b = noTransit.getDist()[idx];
            int degree = graph.degreeOf(idx);
            System.out.printf("%-6s %14s %14s %8d%n", oks.getId(),
                    Double.isInfinite(a) ? "—" : String.format("%.0f м", a),
                    Double.isInfinite(b) ? "—" : String.format("%.0f м", b),
                    degree);
            if (Double.isInfinite(b) && !Double.isInfinite(a)) {
                lostWithoutTransit.add(oks.getId());
            }
        }
        System.out.println("=====================================================");
        System.out.println();

        assertThat(lostWithoutTransit)
                .as("точки, достижимые только транзитом через чужое здание")
                .isEmpty();
    }
}
