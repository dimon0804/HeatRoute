package ru.lct.heatroute.variant;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.calc.CostCalculator;
import ru.lct.heatroute.calc.NetworkMaterializer;
import ru.lct.heatroute.calc.ReconstructionCalculator;
import ru.lct.heatroute.domain.model.FutureOks;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.domain.reference.ReferenceProperties.DiameterRow;
import ru.lct.heatroute.domain.result.CalculationVariant;
import ru.lct.heatroute.domain.result.ChamberReconstructionResult;
import ru.lct.heatroute.domain.result.NewChamberResult;
import ru.lct.heatroute.domain.result.NewSegment;
import ru.lct.heatroute.domain.result.ReconstructionResult;
import ru.lct.heatroute.domain.result.TechnicalNodeResult;
import ru.lct.heatroute.domain.result.TieInResult;
import ru.lct.heatroute.domain.result.VariantSummary;
import ru.lct.heatroute.geo.Geo;
import ru.lct.heatroute.geo.GeoProperties;
import ru.lct.heatroute.routing.ObstacleField;
import ru.lct.heatroute.routing.RoutingGraph;
import ru.lct.heatroute.routing.RoutingProperties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Полный расчёт: от разобранной обстановки до ранжированных вариантов подключения.
 * <p>
 * Порядок работы:
 * <ol>
 *   <li>строится поле препятствий и граф видимости;</li>
 *   <li>формируются кандидаты точек врезки;</li>
 *   <li>порождаются разбиения ОКС по независимым частям сети — от «все вместе»
 *       до нескольких кластеров по расстоянию в графе;</li>
 *   <li>для каждого разбиения и каждой точки врезки строится дерево и считается
 *       полная стоимость с реконструкцией существующей сети;</li>
 *   <li>содержательно совпадающие решения отсеиваются, лучшие три ранжируются по S.</li>
 * </ol>
 */
@Slf4j
@Component
public class VariantPlanner {

    private final ReferenceCatalog catalog;
    private final GeoProperties geoProps;
    private final RoutingProperties routingProps;
    private final TieInCandidateFinder tieInFinder;
    private final SteinerTreeBuilder treeBuilder;
    private final NetworkMaterializer materializer;
    private final ReconstructionCalculator reconstruction;
    private final CostCalculator costCalculator;

    public VariantPlanner(ReferenceCatalog catalog,
                          GeoProperties geoProps,
                          RoutingProperties routingProps,
                          TieInCandidateFinder tieInFinder,
                          SteinerTreeBuilder treeBuilder,
                          NetworkMaterializer materializer,
                          ReconstructionCalculator reconstruction,
                          CostCalculator costCalculator) {
        this.catalog = catalog;
        this.geoProps = geoProps;
        this.routingProps = routingProps;
        this.tieInFinder = tieInFinder;
        this.treeBuilder = treeBuilder;
        this.materializer = materializer;
        this.reconstruction = reconstruction;
        this.costCalculator = costCalculator;
    }

    /** Побочные данные расчёта, нужные интерфейсу и отчётам. */
    @Value
    public static class Plan {
        List<CalculationVariant> variants;
        int designDiameter;
        int graphNodes;
        int graphEdges;
        int tieInCandidates;
        long millis;
    }

    public Plan plan(InputScene scene) {
        long started = System.nanoTime();

        // --- 1. Расчётный диаметр клиренсов ----------------------------------------------
        // Клиренс зависит от условного диаметра трассы, а диаметр известен только после
        // построения дерева. Граф строится один раз, поэтому клиренсы берутся по ДУ
        // магистрали — наибольшему в варианте. Маршрут, допустимый для него, допустим
        // и для всех меньших диаметров, значит нарушение нормы невозможно по построению.
        int designDn = routingProps.getInitialDesignDn() > 0
                ? routingProps.getInitialDesignDn()
                : catalog.selectForFlow(scene.totalFutureFlowTph())
                .map(DiameterRow::getDn).orElse(catalog.largest().getDn());

        ObstacleField field = new ObstacleField(scene, catalog, geoProps, routingProps);

        // --- 2. Граф маршрутизации --------------------------------------------------------
        List<TieInCandidate> candidates = tieInFinder.find(scene);
        List<RoutingGraph.Node> extras = new ArrayList<>();
        for (FutureOks oks : scene.getFutureOks()) {
            extras.add(RoutingGraph.terminal(oks.getConnectionPoint().getCoordinate(), oks.getId()));
        }
        for (TieInCandidate c : candidates) {
            extras.add(RoutingGraph.tieInCandidate(c.getLocation(), c.getId()));
        }
        RoutingGraph graph = RoutingGraph.build(field, designDn, extras, routingProps);

        Map<String, Integer> nodeByExternalId = new LinkedHashMap<>();
        for (RoutingGraph.Node n : graph.nodes()) {
            if (n.getExternalId() != null) {
                nodeByExternalId.putIfAbsent(n.getExternalId(), n.getIndex());
            }
        }
        List<TieInCandidate> located = candidates.stream()
                .map(c -> c.withGraphNodeIndex(nodeByExternalId.getOrDefault(c.getId(), -1)))
                .filter(c -> c.getGraphNodeIndex() >= 0)
                .collect(Collectors.toList());

        List<SteinerTreeBuilder.Terminal> terminals = new ArrayList<>();
        Map<String, FutureOks> oksById = new LinkedHashMap<>();
        Map<String, String> terminalNodeIds = new LinkedHashMap<>();
        for (FutureOks oks : scene.getFutureOks()) {
            Integer idx = nodeByExternalId.get(oks.getId());
            oksById.put(oks.getId(), oks);
            terminalNodeIds.put(oks.getId(), oks.getConnectionPointId());
            if (idx != null) {
                terminals.add(new SteinerTreeBuilder.Terminal(idx, oks.getId(), oks.getFlowTph()));
            }
        }

        // --- 3. Разбиения ОКС ---------------------------------------------------------------
        List<List<List<SteinerTreeBuilder.Terminal>>> partitions =
                new OksClustering(graph).partitions(terminals, 3);

        // --- 4. Перебор ---------------------------------------------------------------------
        List<CalculationVariant> produced = new ArrayList<>();
        int variantCounter = 0;

        for (List<List<SteinerTreeBuilder.Terminal>> partition : partitions) {
            CalculationVariant variant = buildVariant(scene, graph, field, located,
                    partition, oksById, terminalNodeIds, "v" + (++variantCounter));
            if (variant != null) {
                produced.add(variant);
            }
        }

        List<CalculationVariant> ranked = rank(produced);
        long millis = (System.nanoTime() - started) / 1_000_000;
        log.info("Расчёт завершён за {} мс: вариантов {}, расчётный ДУ клиренсов {} мм",
                millis, ranked.size(), designDn);

        return new Plan(ranked, designDn, graph.size(), graph.edgeCount() / 2,
                located.size(), millis);
    }

    // =================================================================================

    /**
     * Строит вариант для заданного разбиения ОКС: для каждой группы подбирается лучшая
     * точка врезки, строится дерево, считается стоимость с реконструкцией.
     */
    private CalculationVariant buildVariant(InputScene scene,
                                            RoutingGraph graph,
                                            ObstacleField field,
                                            List<TieInCandidate> candidates,
                                            List<List<SteinerTreeBuilder.Terminal>> partition,
                                            Map<String, FutureOks> oksById,
                                            Map<String, String> terminalNodeIds,
                                            String variantId) {
        List<NewSegment> segments = new ArrayList<>();
        List<NewChamberResult> chambers = new ArrayList<>();
        List<TechnicalNodeResult> nodes = new ArrayList<>();
        List<TieInResult> tieIns = new ArrayList<>();
        List<String> unconnected = new ArrayList<>();
        Map<String, Integer> chamberMaxDn = new LinkedHashMap<>();
        NetworkMaterializer.IdSequence ids = new NetworkMaterializer.IdSequence();
        Set<String> tieInSignature = new LinkedHashSet<>();

        // Сколько примыканий у каждого узла врезки уже занято другими частями сети
        // этого же варианта. Без этого учёта две независимые части могут выбрать одну
        // и ту же камеру, и предел раздела 3 ТП будет нарушен суммарно, хотя каждая
        // часть по отдельности его соблюдает.
        Map<Integer, Integer> consumedAtNode = new LinkedHashMap<>();

        for (List<SteinerTreeBuilder.Terminal> group : partition) {
            if (group.isEmpty()) {
                continue;
            }
            GroupResult best = null;
            for (TieInCandidate candidate : candidates) {
                GroupResult result = buildGroup(scene, graph, field, candidate, group,
                        variantId, terminalNodeIds, oksById, ids,
                        consumedAtNode.getOrDefault(candidate.getGraphNodeIndex(), 0));
                if (result == null) {
                    continue;
                }
                if (best == null || result.getScore() < best.getScore()) {
                    best = result;
                }
            }
            if (best == null) {
                group.forEach(t -> unconnected.add(t.getOksId()));
                continue;
            }
            consumedAtNode.merge(best.getRootGraphNode(), best.getRootBranches(), Integer::sum);
            segments.addAll(best.getSegments());
            chambers.addAll(best.getChambers());
            nodes.addAll(best.getTechnicalNodes());
            tieIns.add(best.getTieIn());
            unconnected.addAll(best.getUnconnected());
            chamberMaxDn.putAll(best.getChamberMaxDn());
            tieInSignature.add(best.getTieIn().getExistingObjectId() + "@"
                    + Math.round(best.getTieIn().getPositionFraction() * 100));
        }

        if (segments.isEmpty()) {
            return null;
        }

        ReconstructionCalculator.Result recon =
                reconstruction.compute(scene, tieIns, chamberMaxDn, variantId);

        VariantSummary summary = costCalculator.summarize(variantId, segments, chambers,
                tieIns, recon.getSegments(), recon.getChambers(), unconnected, oksById);

        String fingerprint = tieInSignature + "|" + partition.stream()
                .map(g -> g.stream().map(SteinerTreeBuilder.Terminal::getOksId).sorted()
                        .collect(Collectors.joining(",")))
                .sorted().collect(Collectors.joining(";"));

        return CalculationVariant.builder()
                .variantId(variantId)
                .description(describe(partition, tieIns))
                .segments(segments)
                .chambers(chambers)
                .technicalNodes(nodes)
                .tieIns(tieIns)
                .reconstructions(recon.getSegments())
                .chamberReconstructions(recon.getChambers())
                .summary(summary)
                .structureFingerprint(fingerprint)
                .build();
    }

    /** Результат построения одной независимой части сети. */
    @Value
    private static class GroupResult {
        List<NewSegment> segments;
        List<NewChamberResult> chambers;
        List<TechnicalNodeResult> technicalNodes;
        TieInResult tieIn;
        List<String> unconnected;
        Map<String, Integer> chamberMaxDn;
        double score;
        /** Узел графа, в котором выполнена врезка. */
        int rootGraphNode;
        /** Сколько участков новой сети приведено в этот узел. */
        int rootBranches;
    }

    private GroupResult buildGroup(InputScene scene,
                                   RoutingGraph graph,
                                   ObstacleField field,
                                   TieInCandidate candidate,
                                   List<SteinerTreeBuilder.Terminal> group,
                                   String variantId,
                                   Map<String, String> terminalNodeIds,
                                   Map<String, FutureOks> oksById,
                                   NetworkMaterializer.IdSequence ids,
                                   int alreadyConsumedAtNode) {
        // Раздел 3 ТП: к камере примыкает не более четырёх участков. В точке врезки
        // часть мест уже занята: существующей камере — её текущими примыканиями,
        // новой камере на участке — двумя половинами разрезанного участка.
        int maxDegree = catalog.props().getMaxChamberDegree();
        int occupied = candidate.isUsesExistingChamber()
                ? candidate.getChamberExistingDegree()
                : 2;
        int rootSpareDegree = maxDegree - occupied - alreadyConsumedAtNode;
        if (rootSpareDegree <= 0) {
            return null;
        }

        SteinerTreeBuilder.Result built = treeBuilder.build(graph,
                candidate.getGraphNodeIndex(), group, rootSpareDegree, null);
        if (built.isEmpty()) {
            return null;
        }

        int designDn = catalog.selectForFlow(built.getTree().totalFlow())
                .map(DiameterRow::getDn).orElse(catalog.largest().getDn());
        built.getTree().straighten(graph, routingProps.getMinTurnAngleDeg(),
                (a, b) -> field.isPassable(graph.node(a).getLocation(),
                        graph.node(b).getLocation(), designDn,
                        graph.node(b).getTerminalOksId()));

        // Врезка в существующую камеру не требует новой камеры; иначе камера строится
        // в точке врезки (раздел 8.2 ТП).
        String rootNodeId = candidate.isUsesExistingChamber()
                ? candidate.getExistingChamberId()
                : ids.nextChamber();

        NetworkMaterializer.Materialized m = materializer.materialize(built.getTree(), graph,
                field, variantId, rootNodeId, terminalNodeIds, ids);

        List<NewChamberResult> chambers = new ArrayList<>(m.getChambers());
        Map<String, Integer> chamberMaxDn = new LinkedHashMap<>(m.getChamberMaxDiameter());

        if (!candidate.isUsesExistingChamber()) {
            // Стоимость камеры — по наибольшему ДУ всех примыкающих к ней участков
            // в итоговом варианте, включая разрезанный существующий (раздел 8.2 ТП).
            int dn = Math.max(m.getRootDiameter(), candidate.getExistingDiameter());
            chambers.add(NewChamberResult.builder()
                    .id(rootNodeId)
                    .variantId(variantId)
                    .location(Geo.point(candidate.getLocation()))
                    .diameter(dn)
                    .degree(occupied + built.getTree().childrenOf(
                            candidate.getGraphNodeIndex()).size())
                    .cost(catalog.chamberCost(dn))
                    .build());
            chamberMaxDn.put(rootNodeId, dn);
        }

        TieInResult tieIn = TieInResult.builder()
                .id("tie_" + variantId + "_" + candidate.getId().hashCode())
                .variantId(variantId)
                .location(Geo.point(candidate.getLocation()))
                .existingObjectId(candidate.getExistingObjectId())
                .existingObjectType(candidate.getExistingObjectType())
                .existingDiameter(candidate.getExistingDiameter())
                .requiredDiameter(m.getRootDiameter())
                .positionFraction(candidate.getPositionFraction())
                .addedFlowTph(m.getRootFlow())
                .cost(catalog.tieInCost())
                .build();

        List<String> unconnected = new ArrayList<>(built.getUnreachableOks());
        unconnected.addAll(m.getOverCapacityOks());

        // Быстрая оценка для отбора кандидата врезки: полная стоимость части
        // вместе с реконструкцией существующей сети именно от этой точки.
        ReconstructionCalculator.Result recon = reconstruction.compute(scene,
                List.of(tieIn), chamberMaxDn, variantId);
        VariantSummary partial = costCalculator.summarize(variantId, m.getSegments(), chambers,
                List.of(tieIn), recon.getSegments(), recon.getChambers(), unconnected, oksById);

        return new GroupResult(m.getSegments(), chambers, m.getTechnicalNodes(), tieIn,
                unconnected, chamberMaxDn, partial.getScore(),
                candidate.getGraphNodeIndex(),
                built.getTree().childrenOf(candidate.getGraphNodeIndex()).size());
    }

    private String describe(List<List<SteinerTreeBuilder.Terminal>> partition,
                            List<TieInResult> tieIns) {
        String parts = partition.size() == 1
                ? "все перспективные ОКС подключены одной сетью"
                : String.format("сеть разделена на %d независимые части", partition.size());
        String where = tieIns.stream()
                .map(t -> ("heat_chamber".equals(t.getExistingObjectType())
                        ? "врезка в существующую камеру " : "врезка в участок ")
                        + t.getExistingObjectId())
                .distinct()
                .collect(Collectors.joining("; "));
        return parts + "; " + where;
    }

    /**
     * Отсев содержательно совпадающих решений и ранжирование по S.
     * Раздел 2.8 ТЗ: небольшое смещение одной и той же трассы отдельным вариантом
     * не считается, поэтому сравниваются точки врезки и разбиение ОКС по частям сети.
     */
    private List<CalculationVariant> rank(List<CalculationVariant> variants) {
        Map<String, CalculationVariant> distinct = new LinkedHashMap<>();
        for (CalculationVariant v : variants) {
            distinct.merge(v.getStructureFingerprint(), v,
                    (a, b) -> a.getSummary().getScore() <= b.getSummary().getScore() ? a : b);
        }
        List<CalculationVariant> sorted = new ArrayList<>(distinct.values());
        sorted.sort(Comparator.comparingDouble(v -> v.getSummary().getScore()));

        List<CalculationVariant> out = new ArrayList<>();
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            CalculationVariant v = sorted.get(i);
            out.add(v.withSummary(v.getSummary().withRank(i + 1)));
        }
        return out;
    }

    /** Кластеризация ОКС по расстоянию в графе маршрутизации. */
    private static class OksClustering {
        private final RoutingGraph graph;

        OksClustering(RoutingGraph graph) {
            this.graph = graph;
        }

        /**
         * Разбиения от «все вместе» до {@code maxClusters} групп.
         * Метрика — расстояние в графе, а не по прямой: два здания через реку
         * геометрически рядом, а по трассе далеко.
         */
        List<List<List<SteinerTreeBuilder.Terminal>>> partitions(
                List<SteinerTreeBuilder.Terminal> terminals, int maxClusters) {
            List<List<List<SteinerTreeBuilder.Terminal>>> out = new ArrayList<>();
            if (terminals.isEmpty()) {
                return out;
            }
            out.add(List.of(new ArrayList<>(terminals)));
            if (terminals.size() < 2) {
                return out;
            }

            int n = terminals.size();
            double[][] dist = new double[n][];
            for (int i = 0; i < n; i++) {
                double[] full = graph.distancesFrom(terminals.get(i).getNodeIndex());
                dist[i] = new double[n];
                for (int j = 0; j < n; j++) {
                    dist[i][j] = full[terminals.get(j).getNodeIndex()];
                }
            }

            // Агломеративная кластеризация по среднему расстоянию: устойчива к выбросам
            // и не требует задавать центры, которых в графе не существует.
            List<List<Integer>> clusters = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                clusters.add(new ArrayList<>(List.of(i)));
            }
            List<List<List<Integer>>> snapshots = new ArrayList<>();
            while (clusters.size() > 1) {
                int bestA = -1;
                int bestB = -1;
                double bestDist = Double.POSITIVE_INFINITY;
                for (int a = 0; a < clusters.size(); a++) {
                    for (int b = a + 1; b < clusters.size(); b++) {
                        double d = averageDistance(dist, clusters.get(a), clusters.get(b));
                        if (d < bestDist) {
                            bestDist = d;
                            bestA = a;
                            bestB = b;
                        }
                    }
                }
                if (bestA < 0) {
                    break;
                }
                clusters.get(bestA).addAll(clusters.get(bestB));
                clusters.remove(bestB);
                if (clusters.size() <= maxClusters) {
                    List<List<Integer>> copy = new ArrayList<>();
                    clusters.forEach(c -> copy.add(new ArrayList<>(c)));
                    snapshots.add(copy);
                }
            }

            for (List<List<Integer>> snapshot : snapshots) {
                if (snapshot.size() < 2) {
                    continue;
                }
                List<List<SteinerTreeBuilder.Terminal>> partition = new ArrayList<>();
                for (List<Integer> cluster : snapshot) {
                    partition.add(cluster.stream().map(terminals::get)
                            .collect(Collectors.toList()));
                }
                out.add(partition);
            }
            return out;
        }

        private double averageDistance(double[][] dist, List<Integer> a, List<Integer> b) {
            double sum = 0;
            int count = 0;
            for (int i : a) {
                for (int j : b) {
                    double d = dist[i][j];
                    if (Double.isInfinite(d)) {
                        return Double.POSITIVE_INFINITY;
                    }
                    sum += d;
                    count++;
                }
            }
            return count == 0 ? Double.POSITIVE_INFINITY : sum / count;
        }
    }
}
