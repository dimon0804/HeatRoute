package ru.lct.heatroute.variant;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.calc.CostCalculator;
import ru.lct.heatroute.calc.NetworkMaterializer;
import ru.lct.heatroute.calc.ReconstructionCalculator;
import ru.lct.heatroute.depth.DepthPlanner;
import ru.lct.heatroute.depth.UtilityCrossing;
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
    private final VariantRenumberer renumberer;
    private final CrossingRepair crossingRepair;
    private final DepthPlanner depthPlanner;

    public VariantPlanner(ReferenceCatalog catalog,
                          GeoProperties geoProps,
                          RoutingProperties routingProps,
                          TieInCandidateFinder tieInFinder,
                          SteinerTreeBuilder treeBuilder,
                          NetworkMaterializer materializer,
                          ReconstructionCalculator reconstruction,
                          CostCalculator costCalculator,
                          VariantRenumberer renumberer,
                          CrossingRepair crossingRepair,
                          DepthPlanner depthPlanner) {
        this.catalog = catalog;
        this.geoProps = geoProps;
        this.routingProps = routingProps;
        this.tieInFinder = tieInFinder;
        this.treeBuilder = treeBuilder;
        this.materializer = materializer;
        this.reconstruction = reconstruction;
        this.costCalculator = costCalculator;
        this.renumberer = renumberer;
        this.crossingRepair = crossingRepair;
        this.depthPlanner = depthPlanner;
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
        /** Режим с учётом глубины (дополнительная задача). */
        boolean withDepth;
        /** Пересечения с существующими коммуникациями по глубине, по варианту. */
        Map<String, List<UtilityCrossing>> crossingsByVariant;
        /** Участки, для которых допустимый профиль по глубине не найден, по варианту. */
        Map<String, List<String>> depthUnresolvedByVariant;
    }

    /** Обратный вызов прогресса: расчёт длится десятки секунд, интерфейсу нужен отклик. */
    public interface Progress {
        void report(double fraction, String stage);

        Progress NONE = (f, s) -> { };
    }

    public Plan plan(InputScene scene) {
        return plan(scene, Progress.NONE, false);
    }

    public Plan plan(InputScene scene, Progress progress) {
        return plan(scene, progress, false);
    }

    /**
     * @param withDepth рассчитать профиль по глубине (дополнительная задача).
     *                  Стоимость участков пересчитывается с коэффициентом по глубине,
     *                  и ранжирование после этого может измениться: выигрывает вариант,
     *                  у которого пересечений с коммуникациями меньше или они дешевле.
     */
    public Plan plan(InputScene scene, Progress progress, boolean withDepth) {
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
        progress.report(0.05, "Построение поля препятствий");

        // --- 2. Граф маршрутизации --------------------------------------------------------
        List<TieInCandidate> candidates = tieInFinder.find(scene);
        progress.report(0.10, "Поиск кандидатов точек врезки");
        List<RoutingGraph.Node> extras = new ArrayList<>();
        for (FutureOks oks : scene.getFutureOks()) {
            extras.add(RoutingGraph.terminal(oks.getConnectionPoint().getCoordinate(), oks.getId()));
        }
        for (TieInCandidate c : candidates) {
            // Точка врезки лежит прямо на существующей сети, то есть внутри её
            // защитной полосы. Первое звено новой трассы от неё эту полосу не нарушает:
            // сеть в этом месте и присоединяется.
            extras.add(RoutingGraph.tieInCandidateOnNetwork(c.getLocation(), c.getId()));
        }
        RoutingGraph graph = RoutingGraph.build(field, designDn, extras, routingProps);
        progress.report(0.45, "Граф маршрутизации построен");

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
        progress.report(0.50, "Кластеризация перспективных ОКС");

        // Расстояния от каждого терминала до всех узлов графа. Считаются один раз
        // и служат и кластеризации, и отсеву кандидатов врезки.
        Map<String, double[]> distanceFromTerminal = new LinkedHashMap<>();
        for (SteinerTreeBuilder.Terminal t : terminals) {
            distanceFromTerminal.put(t.getOksId(), graph.distancesFrom(t.getNodeIndex()));
        }

        List<List<List<SteinerTreeBuilder.Terminal>>> partitions =
                new OksClustering(graph, distanceFromTerminal).partitions(terminals, 3);

        // --- 4. Перебор ---------------------------------------------------------------------
        List<CalculationVariant> produced = new ArrayList<>();
        int variantCounter = 0;

        for (List<List<SteinerTreeBuilder.Terminal>> partition : partitions) {
            CalculationVariant variant = buildVariant(scene, graph, field, located,
                    partition, oksById, terminalNodeIds, distanceFromTerminal,
                    "v" + (++variantCounter));
            if (variant != null) {
                produced.add(variant);
            }
            progress.report(0.50 + 0.45 * variantCounter / partitions.size(),
                    String.format("Построение вариантов: %d из %d",
                            variantCounter, partitions.size()));
        }

        Map<String, List<UtilityCrossing>> crossings = new LinkedHashMap<>();
        Map<String, List<String>> depthUnresolved = new LinkedHashMap<>();

        if (withDepth) {
            progress.report(0.95, "Построение профиля по глубине");
            List<CalculationVariant> withProfile = new ArrayList<>(produced.size());
            for (CalculationVariant variant : produced) {
                DepthPlanner.Result depth = depthPlanner.apply(variant, scene);
                CalculationVariant updated = depth.getVariant();
                // Стоимость участков изменилась: пересчитываем сводку по разделу 8 ТП.
                updated = updated.withSummary(costCalculator.summarize(
                        updated.getVariantId(), updated.getSegments(), updated.getChambers(),
                        updated.getTieIns(), updated.getReconstructions(),
                        updated.getChamberReconstructions(),
                        variant.getSummary().getUnconnectedOksIds(), oksById));
                withProfile.add(updated);
                crossings.put(updated.getVariantId(), depth.getCrossings());
                depthUnresolved.put(updated.getVariantId(), depth.getUnresolved());
            }
            produced = withProfile;
        }

        List<CalculationVariant> ranked = rank(produced);
        progress.report(0.98, "Ранжирование вариантов");
        long millis = (System.nanoTime() - started) / 1_000_000;
        log.info("Расчёт завершён за {} мс: вариантов {}, расчётный ДУ клиренсов {} мм{}",
                millis, ranked.size(), designDn, withDepth ? ", с учётом глубины" : "");

        return new Plan(ranked, designDn, graph.size(), graph.edgeCount() / 2,
                located.size(), millis, withDepth, crossings, depthUnresolved);
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
                                            Map<String, double[]> distanceFromTerminal,
                                            String variantId) {
        List<NewSegment> segments = new ArrayList<>();
        List<NewChamberResult> chambers = new ArrayList<>();
        List<TechnicalNodeResult> nodes = new ArrayList<>();
        List<TieInResult> tieIns = new ArrayList<>();
        List<String> unconnected = new ArrayList<>();
        Map<String, Integer> chamberMaxDn = new LinkedHashMap<>();
        NetworkMaterializer.IdSequence ids = new NetworkMaterializer.IdSequence(variantId);
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
            for (TieInCandidate candidate : shortlist(candidates, group, distanceFromTerminal)) {
                GroupResult result = buildGroup(scene, graph, field, candidate, group,
                        variantId, terminalNodeIds, oksById, ids,
                        consumedAtNode.getOrDefault(candidate.getGraphNodeIndex(), 0),
                        segments);
                if (result == null) {
                    continue;
                }
                if (best == null || betterThan(result, best)) {
                    best = result;
                }
            }
            if (best != null && best.getCrossingsWithAccepted() > 0) {
                // Независимые части сети объединить нельзя: у каждой своя точка врезки,
                // и объединение дало бы два пути до одного ОКС вопреки разделу 2.2 ТЗ.
                // Раз ни одна точка врезки не даёт части без пересечений, это разбиение
                // ОКС непригодно целиком. Разбиение «все одной сетью» пересечений между
                // частями не имеет по построению, поэтому хотя бы один вариант останется.
                log.debug("Разбиение отброшено: часть сети пересекает уже принятые "
                        + "участки при любой точке врезки (вариант {})", variantId);
                return null;
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

        // Перебор кандидатов врезки расходует идентификаторы, поэтому итоговые объекты
        // нумеруются заново — уже после того, как состав варианта окончательно известен.
        VariantRenumberer.Renumbered renumbered =
                renumberer.renumber(variantId, segments, chambers, nodes, tieIns);
        segments = renumbered.getSegments();
        chambers = renumbered.getChambers();
        nodes = renumbered.getTechnicalNodes();
        tieIns = renumbered.getTieIns();
        chamberMaxDn = renumberer.remapChamberDiameters(
                chamberMaxDn, renumbered.getNodeIdMapping());

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

    /**
     * Кандидаты врезки, отобранные по нижней оценке стоимости подключения группы.
     * <p>
     * Полная проверка кандидата — это построение дерева, материализация, расчёт
     * реконструкции и стоимости. На десятках кандидатов и нескольких группах это
     * основная доля времени расчёта. Нижняя оценка — сумма расстояний в графе
     * от кандидата до терминалов группы — считается по уже готовой матрице
     * расстояний и отбирает те же места, что и полная проверка, но мгновенно.
     */
    private List<TieInCandidate> shortlist(List<TieInCandidate> candidates,
                                           List<SteinerTreeBuilder.Terminal> group,
                                           Map<String, double[]> distanceFromTerminal) {
        int limit = Math.max(1, routingProps.getTieInShortlistSize());
        if (candidates.size() <= limit) {
            return candidates;
        }
        List<TieInCandidate> sorted = new ArrayList<>(candidates);
        Map<String, Double> estimate = new LinkedHashMap<>();
        for (TieInCandidate candidate : sorted) {
            double sum = 0;
            boolean reachable = true;
            for (SteinerTreeBuilder.Terminal terminal : group) {
                double[] distances = distanceFromTerminal.get(terminal.getOksId());
                double d = distances == null
                        ? Double.POSITIVE_INFINITY : distances[candidate.getGraphNodeIndex()];
                if (Double.isInfinite(d)) {
                    reachable = false;
                    break;
                }
                sum += d;
            }
            estimate.put(candidate.getId(), reachable ? sum : Double.POSITIVE_INFINITY);
        }
        sorted.sort(Comparator.comparingDouble(c -> estimate.get(c.getId())));
        return sorted.stream()
                .filter(c -> !Double.isInfinite(estimate.get(c.getId())))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Порядок предпочтения кандидатов: сначала подключить все ОКС, затем не нарушать
     * запрет пересечений, и только потом — дешевле.
     * <p>
     * Порядок именно такой, потому что цена ошибок разная. Неподключенный ОКС стоит
     * не менее 100 млн руб. штрафа (раздел 8.3 ТП) и прямо ухудшает решение задачи.
     * Пересечение трасс — нарушение правила, но его можно обойти другим разбиением
     * ОКС; жертвовать ради него подключением объекта нельзя.
     */
    private boolean betterThan(GroupResult candidate, GroupResult current) {
        if (candidate.getUnconnected().size() != current.getUnconnected().size()) {
            return candidate.getUnconnected().size() < current.getUnconnected().size();
        }
        if (candidate.getCrossingsWithAccepted() != current.getCrossingsWithAccepted()) {
            return candidate.getCrossingsWithAccepted() < current.getCrossingsWithAccepted();
        }
        return candidate.getScore() < current.getScore();
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
        /**
         * Сколько участков этой части пересекает уже принятые части сети.
         * Раздел 2.3 ТЗ запрещает пересечения вне общего узла, а объединить
         * независимые части нельзя: у каждой своя точка врезки, и объединение
         * дало бы два пути до одного ОКС.
         */
        int crossingsWithAccepted;
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
                                   int alreadyConsumedAtNode,
                                   List<NewSegment> acceptedSegments) {
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

        // Порядок подключения терминалов меняет форму дерева, поэтому эвристика
        // запускается несколько раз с разными затравками, а лучшее дерево по длине
        // отбирается здесь. Разброс между запусками на конкурсном наборе достигает
        // десятых долей, и брать первый попавшийся результат незачем.
        SteinerTreeBuilder.Result built = null;
        double bestLength = Double.POSITIVE_INFINITY;
        int restarts = Math.max(1, routingProps.getSteinerRestarts());
        for (int attempt = 0; attempt < restarts; attempt++) {
            SteinerTreeBuilder.Terminal seed = attempt == 0 || attempt > group.size()
                    ? null : group.get((attempt - 1) % group.size());
            SteinerTreeBuilder.Result trial = treeBuilder.build(graph,
                    candidate.getGraphNodeIndex(), group, rootSpareDegree, seed);
            if (trial.isEmpty()) {
                continue;
            }
            double length = trial.getTree().totalLength(graph)
                    + trial.getUnreachableOks().size() * 1e6;
            if (length < bestLength) {
                bestLength = length;
                built = trial;
            }
        }
        if (built == null || built.isEmpty()) {
            return null;
        }

        int designDn = catalog.selectForFlow(built.getTree().totalFlow())
                .map(DiameterRow::getDn).orElse(catalog.largest().getDn());

        RouteTree tree = built.getTree();
        java.util.function.BiPredicate<Integer, Integer> passable = (a, b) -> field.isPassable(
                tree.locationOf(graph, a), tree.locationOf(graph, b), designDn,
                tree.isSynthetic(b) ? null : graph.node(b).getTerminalOksId());

        // Раздел 2.3 ТЗ: пересекающиеся маршруты объединяются в общую сеть.
        // Выполняется до спрямления: объединение убирает лишние звенья, и спрямлять
        // потом есть смысл, а наоборот — нет.
        crossingRepair.repair(tree, graph, passable);
        tree.straighten(graph, routingProps.getMinTurnAngleDeg(), passable);

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
                .id(ids.nextTieIn())
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
                built.getTree().childrenOf(candidate.getGraphNodeIndex()).size(),
                countCrossings(m.getSegments(), acceptedSegments));
    }

    /** Сколько участков новой части пересекает уже принятые участки варианта. */
    private int countCrossings(List<NewSegment> fresh, List<NewSegment> accepted) {
        if (accepted.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (NewSegment a : fresh) {
            for (NewSegment b : accepted) {
                if (a.getGeometry().intersects(b.getGeometry())) {
                    count++;
                }
            }
        }
        return count;
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
        private final Map<String, double[]> distanceFromTerminal;

        OksClustering(RoutingGraph graph, Map<String, double[]> distanceFromTerminal) {
            this.graph = graph;
            this.distanceFromTerminal = distanceFromTerminal;
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
                double[] full = distanceFromTerminal.get(terminals.get(i).getOksId());
                if (full == null) {
                    full = graph.distancesFrom(terminals.get(i).getNodeIndex());
                }
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
