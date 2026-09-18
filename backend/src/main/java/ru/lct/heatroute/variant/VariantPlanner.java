package ru.lct.heatroute.variant;

import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.calc.CostCalculator;
import ru.lct.heatroute.calc.NetworkMaterializer;
import ru.lct.heatroute.calc.ReconstructionCalculator;
import ru.lct.heatroute.depth.DepthPlanner;
import ru.lct.heatroute.depth.UtilityCrossing;
import ru.lct.heatroute.domain.model.FutureOks;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.domain.reference.ReferenceProperties;
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
    private final TreeImprover treeImprover;
    private final JunctionRelocator junctionRelocator;
    private final DepthPlanner depthPlanner;

    /** Раздел 2.11 ТЗ: содержательно отличающихся вариантов не больше трёх. */
    private static final int MAX_VARIANTS = 3;

    /**
     * Почему объект остаётся без подключения. Раздел 2.9 ТЗ требует обработать такой
     * случай, а обработать — значит не только назвать объект, но и сказать, что с ним
     * не так: без этого список идентификаторов бесполезен.
     */
    private static final String REASON_OUTSIDE_GRAPH =
            "Точка подключения недостижима: от неё нет ни одного допустимого отрезка "
                    + "до остальной обстановки — её со всех сторон закрывают ограничения";
    private static final String REASON_NO_ROUTE =
            "Маршрут не найден: нет пути, проходимого по клиренсу расчётного диаметра, "
                    + "либо у всех допустимых узлов сети исчерпан предел примыканий";
    private static final String REASON_NO_TIE_IN =
            "Не нашлось допустимой точки врезки: к ним нет пути либо у всех исчерпан "
                    + "предел примыканий";
    private static final String REASON_OVER_CAPACITY =
            "Расход превышает пропускную способность наибольшего условного диаметра "
                    + "справочника (таблица 4.1)";

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
                          TreeImprover treeImprover,
                          JunctionRelocator junctionRelocator,
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
        this.treeImprover = treeImprover;
        this.junctionRelocator = junctionRelocator;
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

    /**
     * Что именно считать. Всё, что задаётся на запуск, а не конфигурацией сервиса.
     */
    @Value
    @Builder
    public static class Options {

        /**
         * Рассчитать профиль по глубине (дополнительная задача). Стоимость участков
         * пересчитывается с коэффициентом по глубине, и ранжирование после этого может
         * измениться: выигрывает вариант, у которого пересечений меньше или они дешевле.
         */
        boolean withDepth;

        /**
         * Условный диаметр, по которому берутся клиренсы при построении графа, мм.
         * Ноль — подобрать по суммарному расходу перспективных ОКС.
         */
        int designDiameter;

        /**
         * Зоны, через которые трассе проходить нельзя, в рабочей проекции.
         * <p>
         * Задаются пользователем: «здесь копать нельзя» — стройплощадка, охранная зона,
         * участок, который город не отдаёт. Для расчёта это такое же препятствие, как
         * здание, с одной разницей: здание есть во входных данных, а зона появляется
         * на запуск и в граф видимости не попадает.
         */
        @Builder.Default
        List<Geometry> forbiddenZones = List.of();

        public static Options flat() {
            return Options.builder().build();
        }

        public static Options depth() {
            return Options.builder().withDepth(true).build();
        }
    }

    public Plan plan(InputScene scene) {
        return plan(scene, Progress.NONE, Options.flat());
    }

    public Plan plan(InputScene scene, Progress progress) {
        return plan(scene, progress, Options.flat());
    }

    public Plan plan(InputScene scene, Progress progress, boolean withDepth) {
        return plan(scene, progress,
                withDepth ? Options.depth() : Options.flat());
    }

    public Plan plan(InputScene scene, Progress progress, Options options) {
        long started = System.nanoTime();
        boolean withDepth = options.isWithDepth();
        RouteBarrier userZones = RouteBarrier.ofZones(options.getForbiddenZones());

        // --- 1. Расчётный диаметр клиренсов ----------------------------------------------
        // Клиренс зависит от условного диаметра трассы, а диаметр известен только после
        // построения дерева. Граф строится один раз, поэтому клиренсы берутся по ДУ
        // магистрали — наибольшему в варианте. Маршрут, допустимый для него, допустим
        // и для всех меньших диаметров, значит нарушение нормы невозможно по построению.
        // Порядок источников: запрос важнее конфигурации, конфигурация важнее расчёта
        // по суммарному расходу. Заданный вручную диаметр — способ посмотреть, что будет
        // при более строгих клиренсах, не пересобирая сервис.
        int designDn = firstPositive(
                options.getDesignDiameter(),
                routingProps.getInitialDesignDn(),
                catalog.selectForFlow(scene.totalFutureFlowTph())
                        .map(DiameterRow::getDn).orElse(catalog.largest().getDn()));

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

        // ОКС, для которого не нашлось узла в графе, — это объект, до которого маршрута
        // нет вообще: его точка подключения оказалась вне досягаемости всех остальных
        // узлов. Пропустить его молча нельзя: раздел 2.9 ТЗ требует сохранить
        // построенную часть результата и назвать проблемные объекты поимённо,
        // а исчезнувший из выдачи объект не заметят вовсе.
        List<String> outsideGraph = new ArrayList<>();

        for (FutureOks oks : scene.getFutureOks()) {
            Integer idx = nodeByExternalId.get(oks.getId());
            oksById.put(oks.getId(), oks);
            terminalNodeIds.put(oks.getId(), oks.getConnectionPointId());
            if (idx != null) {
                terminals.add(new SteinerTreeBuilder.Terminal(idx, oks.getId(), oks.getFlowTph()));
            } else {
                outsideGraph.add(oks.getId());
            }
        }
        if (!outsideGraph.isEmpty()) {
            log.warn("Точка подключения вне графа маршрутизации у {} ОКС: {}. "
                            + "Они пойдут в результат как неподключенные со штрафом",
                    outsideGraph.size(), outsideGraph);
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
                new OksClustering(graph, distanceFromTerminal)
                        .partitions(terminals, routingProps.getMaxOksPartitions());

        // --- 4. Перебор ---------------------------------------------------------------------
        List<CalculationVariant> produced = new ArrayList<>();
        Map<String, List<List<SteinerTreeBuilder.Terminal>>> partitionByVariant =
                new LinkedHashMap<>();
        int variantCounter = 0;

        for (List<List<SteinerTreeBuilder.Terminal>> partition : partitions) {
            String variantId = "v" + (++variantCounter);
            CalculationVariant variant = buildVariant(scene, graph, field, located,
                    partition, oksById, terminalNodeIds, distanceFromTerminal,
                    variantId, userZones, null, outsideGraph);
            partitionByVariant.put(variantId, partition);
            if (variant != null) {
                produced.add(variant);
            }
            progress.report(0.50 + 0.45 * variantCounter / partitions.size(),
                    String.format("Построение вариантов: %d из %d",
                            variantCounter, partitions.size()));
        }

        // Разбиения — основной источник различий; если их не хватило, различие даёт
        // форма дерева при той же точке врезки.
        variantCounter = addSeededVariants(produced, partitionByVariant, partitions, scene,
                graph, field, located, oksById, terminalNodeIds, distanceFromTerminal,
                variantCounter, userZones, outsideGraph);

        Map<String, List<UtilityCrossing>> crossings = new LinkedHashMap<>();
        Map<String, List<String>> depthUnresolved = new LinkedHashMap<>();

        if (withDepth) {
            progress.report(0.95, "Построение профиля по глубине");
            List<CalculationVariant> withProfile = new ArrayList<>(produced.size());
            for (CalculationVariant variant : produced) {
                withProfile.add(applyDepth(variant, scene, oksById,
                        variant.getSummary().getUnconnectedOksIds(), crossings, depthUnresolved));
            }
            produced = withProfile;

            // Профиль умеет сказать маршрутизации, где ей не хватило места.
            produced = relayUnfittingManoeuvres(produced, scene, graph, field, located,
                    partitionByVariant, oksById, terminalNodeIds, distanceFromTerminal,
                    crossings, depthUnresolved, progress, userZones, outsideGraph);
        }

        List<CalculationVariant> ranked = rank(produced, depthViolations(crossings));
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
                                            String variantId,
                                            RouteBarrier zones,
                                            Integer forcedSeed,
                                            List<String> outsideGraph) {
        List<NewSegment> segments = new ArrayList<>();
        List<NewChamberResult> chambers = new ArrayList<>();
        List<TechnicalNodeResult> nodes = new ArrayList<>();
        List<TieInResult> tieIns = new ArrayList<>();
        // Причина известна ровно там, где объект выбывает; дальше её остаётся донести.
        Map<String, String> unconnectedReasons = new LinkedHashMap<>();
        outsideGraph.forEach(id -> unconnectedReasons.put(id, REASON_OUTSIDE_GRAPH));
        List<String> unconnected = new ArrayList<>(outsideGraph);
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
            List<TieInCandidate> shortlist = shortlist(candidates, group, distanceFromTerminal);
            GroupResult best = bestOverCandidates(scene, graph, field, shortlist, group,
                    variantId, terminalNodeIds, oksById, ids, consumedAtNode, segments,
                    zones, forcedSeed);

            if (best != null && best.getCrossingsWithAccepted() > 0) {
                // Независимые части сети объединить нельзя: у каждой своя точка врезки,
                // и объединение дало бы два пути до одного ОКС вопреки разделу 2.2 ТЗ.
                // Значит, часть строится заново — с принятыми участками в роли препятствия.
                // Обойти соседнюю часть почти всегда дешевле, чем потерять разбиение:
                // трасса удлиняется на обход, а вариант остаётся в выдаче.
                GroupResult detour = bestOverCandidates(scene, graph, field, shortlist, group,
                        variantId, terminalNodeIds, oksById, ids, consumedAtNode, segments,
                        zones.plus(RouteBarrier.ofAcceptedParts(segments)), forcedSeed);
                if (detour != null && detour.getCrossingsWithAccepted() == 0) {
                    log.debug("Часть сети перестроена в обход принятых участков: "
                                    + "подключено {} из {} ОКС, S части {} → {} (вариант {})",
                            group.size() - detour.getUnconnected().size(), group.size(),
                            String.format("%.3f", best.getScore()),
                            String.format("%.3f", detour.getScore()), variantId);
                    best = detour;
                } else {
                    // Обхода нет: препятствия и принятые части замкнули коридор.
                    // Разбиение непригодно целиком; разбиение «все одной сетью»
                    // пересечений между частями не имеет по построению, поэтому
                    // хотя бы один вариант в выдаче останется.
                    log.debug("Разбиение отброшено: часть сети не обходит уже принятые "
                            + "участки ни при одной точке врезки (вариант {})", variantId);
                    return null;
                }
            }
            if (best == null) {
                group.forEach(t -> {
                    unconnected.add(t.getOksId());
                    unconnectedReasons.put(t.getOksId(), REASON_NO_TIE_IN);
                });
                continue;
            }
            consumedAtNode.merge(best.getRootGraphNode(), best.getRootBranches(), Integer::sum);
            segments.addAll(best.getSegments());
            chambers.addAll(best.getChambers());
            nodes.addAll(best.getTechnicalNodes());
            tieIns.add(best.getTieIn());
            unconnected.addAll(best.getUnconnected());
            unconnectedReasons.putAll(best.getUnconnectedReasons());
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
                tieIns, recon.getSegments(), recon.getChambers(), unconnected,
                unconnectedReasons, oksById);

        // Отпечаток двухуровневый. Основная часть — точки врезки и разбиение ОКС:
        // по разделу 2.8 ТЗ именно они делают решения содержательно разными. Хвост —
        // форма дерева; он нужен, только когда основных различий на три варианта
        // не набралось, и в ранжировании используется вторым проходом.
        String fingerprint = tieInSignature + "|" + partition.stream()
                .map(g -> g.stream().map(SteinerTreeBuilder.Terminal::getOksId).sorted()
                        .collect(Collectors.joining(",")))
                .sorted().collect(Collectors.joining(";"))
                + "|" + topologySignature(segments);

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
     * <p>
     * Недостижимый терминал не отменяет кандидата. Сначала кандидаты сравниваются
     * по числу терминалов, до которых от них вообще есть путь, и только потом
     * по сумме расстояний до достижимых. Иначе один объект, к которому маршрута нет,
     * обнулял бы все точки врезки группы, и вариант не строился бы вовсе — а раздел
     * 2.9 ТЗ требует ровно обратного: сохранить построенную часть и назвать
     * проблемный объект отдельно.
     */
    private List<TieInCandidate> shortlist(List<TieInCandidate> candidates,
                                           List<SteinerTreeBuilder.Terminal> group,
                                           Map<String, double[]> distanceFromTerminal) {
        int limit = Math.max(1, routingProps.getTieInShortlistSize());
        if (candidates.size() <= limit) {
            return candidates;
        }
        Map<String, int[]> unreachable = new LinkedHashMap<>();
        Map<String, Double> estimate = new LinkedHashMap<>();

        for (TieInCandidate candidate : candidates) {
            double sum = 0;
            int missed = 0;
            for (SteinerTreeBuilder.Terminal terminal : group) {
                double[] distances = distanceFromTerminal.get(terminal.getOksId());
                double d = distances == null
                        ? Double.POSITIVE_INFINITY : distances[candidate.getGraphNodeIndex()];
                if (Double.isInfinite(d)) {
                    missed++;
                } else {
                    sum += d;
                }
            }
            unreachable.put(candidate.getId(), new int[]{missed});
            estimate.put(candidate.getId(), sum);
        }

        List<TieInCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator
                .comparingInt((TieInCandidate c) -> unreachable.get(c.getId())[0])
                .thenComparingDouble(c -> estimate.get(c.getId())));

        // Кандидат, от которого не достижим ни один терминал группы, бесполезен —
        // но только он один и отсеивается.
        return sorted.stream()
                .filter(c -> unreachable.get(c.getId())[0] < group.size())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /** Лучшая точка врезки для части сети при заданном наборе запретов. */
    private GroupResult bestOverCandidates(InputScene scene,
                                           RoutingGraph graph,
                                           ObstacleField field,
                                           List<TieInCandidate> shortlist,
                                           List<SteinerTreeBuilder.Terminal> group,
                                           String variantId,
                                           Map<String, String> terminalNodeIds,
                                           Map<String, FutureOks> oksById,
                                           NetworkMaterializer.IdSequence ids,
                                           Map<Integer, Integer> consumedAtNode,
                                           List<NewSegment> accepted,
                                           RouteBarrier barrier,
                                           Integer forcedSeed) {
        // Запреты одни и те же для всех кандидатов врезки этой части сети, поэтому
        // маска рёбер считается здесь, а не внутри построения каждого дерева.
        boolean[] allowedEdges = barrier.isEmpty() ? null
                : graph.edgeMask((from, to) -> !barrier.blocks(graph.node(from).getLocation(),
                        graph.node(to).getLocation()));
        GroupResult best = null;
        for (TieInCandidate candidate : shortlist) {
            GroupResult result = buildGroup(scene, graph, field, candidate, group,
                    variantId, terminalNodeIds, oksById, ids,
                    consumedAtNode.getOrDefault(candidate.getGraphNodeIndex(), 0),
                    accepted, barrier, allowedEdges, forcedSeed);
            if (result == null) {
                continue;
            }
            if (best == null || betterThan(result, best)) {
                best = result;
            }
        }
        return best;
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
        /** Почему именно эти объекты остались без подключения. */
        Map<String, String> unconnectedReasons;
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
                                   List<NewSegment> acceptedSegments,
                                   RouteBarrier barrier,
                                   boolean[] allowedEdges,
                                   Integer forcedSeed) {
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
        // Клиренс для отводов от новых камер берётся по тому же диаметру, что и граф:
        // маршрут, допустимый для магистрали, допустим и для любой ветви.
        int designDnForClearance = catalog.selectForFlow(
                        group.stream().mapToDouble(SteinerTreeBuilder.Terminal::getFlowTph).sum())
                .map(DiameterRow::getDn).orElse(catalog.largest().getDn());

        SteinerTreeBuilder.Result built = null;
        double bestLength = Double.POSITIVE_INFINITY;
        int restarts = Math.max(1, routingProps.getSteinerRestarts());
        // Заданная затравка означает «построй именно эту форму дерева»: вариант
        // порождается ради непохожести на уже найденные, и выбор лучшего по длине
        // вернул бы ту же сеть, что и обычный проход.
        int firstAttempt = forcedSeed == null ? 0 : forcedSeed;
        int lastAttempt = forcedSeed == null ? restarts : forcedSeed + 1;
        if (firstAttempt > group.size()) {
            return null;
        }
        for (int attempt = firstAttempt; attempt < lastAttempt; attempt++) {
            SteinerTreeBuilder.Terminal seed = attempt == 0 || attempt > group.size()
                    ? null : group.get((attempt - 1) % group.size());
            SteinerTreeBuilder.Result trial = treeBuilder.build(graph,
                    candidate.getGraphNodeIndex(), group, rootSpareDegree, seed,
                    clearanceOf(field, barrier), allowedEdges);
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
        java.util.function.BiPredicate<Integer, Integer> passable = (a, b) -> {
            Coordinate from = tree.locationOf(graph, a);
            Coordinate to = tree.locationOf(graph, b);
            return field.isPassable(from, to, designDn,
                    tree.isSynthetic(b) ? null : graph.node(b).getTerminalOksId())
                    && !barrier.blocks(from, to);
        };

        // Эвристика подключает объекты по одному и назад не оглядывается. Два хода
        // исправляют это по очереди: перецепка переносит ветвь туда, где стало удобнее,
        // перенос развилки ставит её в точку, которая дешевле всего для примыкающих
        // труб. Ходы связаны: перецепленная ветвь меняет оптимум развилки, а сдвинутая
        // развилка меняет, куда выгодно цеплять ветвь, — поэтому они чередуются
        // до исчерпания выигрыша. Оба делаются до правки пересечений: перестроенные
        // ветви могут пересечься заново.
        SteinerTreeBuilder.Passability clearance = (from, to, exemptOks, requiredDn) ->
                field.isPassable(from, to, requiredDn, exemptOks) && !barrier.blocks(from, to);
        for (int round = 0; round < routingProps.getTreePolishRounds(); round++) {
            double gain = treeImprover.improve(tree, graph, clearance)
                    + junctionRelocator.relocate(tree, graph, clearance);
            if (gain <= 0) {
                break;
            }
        }

        // Раздел 2.3 ТЗ: пересекающиеся маршруты объединяются в общую сеть.
        // Выполняется до спрямления: объединение убирает лишние звенья, и спрямлять
        // потом есть смысл, а наоборот — нет.
        crossingRepair.repair(tree, graph, passable);
        tree.straighten(graph, routingProps.getMinTurnAngleDeg(), passable);

        // Ветви без подключаемых объектов — побочный результат перестроений.
        // Расход по ним нулевой, но труба строится и стоит денег.
        int pruned = tree.pruneEmptyBranches();
        if (pruned > 0) {
            log.debug("Отсечено тупиковых узлов без потребителей: {}", pruned);
        }

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

        Map<String, String> reasons = new LinkedHashMap<>();
        built.getUnreachableOks().forEach(id -> reasons.put(id, REASON_NO_ROUTE));
        m.getOverCapacityOks().forEach(id -> reasons.put(id, REASON_OVER_CAPACITY));

        // Быстрая оценка для отбора кандидата врезки: полная стоимость части
        // вместе с реконструкцией существующей сети именно от этой точки.
        ReconstructionCalculator.Result recon = reconstruction.compute(scene,
                List.of(tieIn), chamberMaxDn, variantId);
        VariantSummary partial = costCalculator.summarize(variantId, m.getSegments(), chambers,
                List.of(tieIn), recon.getSegments(), recon.getChambers(), unconnected, oksById);

        return new GroupResult(m.getSegments(), chambers, m.getTechnicalNodes(), tieIn,
                unconnected, reasons, chamberMaxDn, partial.getScore(),
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

    /** Профиль по глубине с пересчётом стоимости и записью пересечений. */
    private CalculationVariant applyDepth(CalculationVariant variant,
                                          InputScene scene,
                                          Map<String, FutureOks> oksById,
                                          List<String> unconnected,
                                          Map<String, List<UtilityCrossing>> crossings,
                                          Map<String, List<String>> depthUnresolved) {
        DepthPlanner.Result depth = depthPlanner.apply(variant, scene);
        CalculationVariant updated = depth.getVariant();
        // Стоимость участков изменилась: пересчитываем сводку по разделу 8 ТП.
        updated = updated.withSummary(costCalculator.summarize(
                updated.getVariantId(), updated.getSegments(), updated.getChambers(),
                updated.getTieIns(), updated.getReconstructions(),
                updated.getChamberReconstructions(), unconnected,
                variant.getSummary().getUnconnectedReasons(), oksById));
        crossings.put(updated.getVariantId(), depth.getCrossings());
        depthUnresolved.put(updated.getVariantId(), depth.getUnresolved());
        return updated;
    }

    /**
     * Перекладка трассы там, где манёвр по глубине не помещается.
     * <p>
     * Просвет не выдерживается, когда пересечение приходится на первые метры нитки:
     * на смену глубины с 3,0 до 2,0 м при уклоне не круче 0,10 м/м нужно десять метров
     * разбега, а до точки врезки или узла ветвления их нет. Само пересечение при этом
     * не обязано быть именно здесь: коммуникация тянется дальше, и пересечь её можно
     * там, где разбег есть.
     * <p>
     * Поэтому вокруг каждого неудавшегося пересечения ставится зона запрета радиусом
     * в недостающий разбег, и вариант строится заново. Радиус не подобран, а посчитан:
     * меньший не гарантирует разбега, больший запрещает годные трассы.
     * <p>
     * Проходов несколько: обойдя одну зону, трасса может упереться в такую же рядом,
     * и зоны накапливаются. Перестроенный вариант принимается, только если нарушений
     * стало меньше и ни один ОКС не потерял подключение. Обход зоны бывает дороже,
     * но вертикальный просвет — обязательное правило, а показатель S — критерий
     * сравнения, и правилу уступает.
     */
    private List<CalculationVariant> relayUnfittingManoeuvres(
            List<CalculationVariant> produced,
            InputScene scene,
            RoutingGraph graph,
            ObstacleField field,
            List<TieInCandidate> candidates,
            Map<String, List<List<SteinerTreeBuilder.Terminal>>> partitionByVariant,
            Map<String, FutureOks> oksById,
            Map<String, String> terminalNodeIds,
            Map<String, double[]> distanceFromTerminal,
            Map<String, List<UtilityCrossing>> crossings,
            Map<String, List<String>> depthUnresolved,
            Progress progress,
            RouteBarrier userZones,
            List<String> outsideGraph) {

        List<CalculationVariant> out = new ArrayList<>(produced.size());
        int passes = Math.max(1, routingProps.getDepthRelayPasses());

        for (CalculationVariant variant : produced) {
            String variantId = variant.getVariantId();
            List<List<SteinerTreeBuilder.Terminal>> partition = partitionByVariant.get(variantId);

            CalculationVariant bestVariant = variant;
            List<UtilityCrossing> bestCrossings = crossings.getOrDefault(variantId, List.of());
            List<String> bestUnresolved = depthUnresolved.getOrDefault(variantId, List.of());
            long bestViolations = unmetClearances(bestCrossings);
            int allowedUnconnected = variant.getSummary().getUnconnectedOksIds().size();

            List<Geometry> zones = new ArrayList<>(forbiddenZones(bestCrossings));
            if (zones.isEmpty() || partition == null) {
                out.add(variant);
                continue;
            }

            for (int pass = 0; pass < passes && bestViolations > 0; pass++) {
                progress.report(0.96, "Перекладка трасс по результатам профиля");
                CalculationVariant rebuilt = buildVariant(scene, graph, field, candidates,
                        partition, oksById, terminalNodeIds, distanceFromTerminal, variantId,
                        userZones.plus(RouteBarrier.ofZones(zones)), null, outsideGraph);
                if (rebuilt == null) {
                    // Зоны замкнули коридор: трассы в обход нет. Дальше запреты только
                    // строже, поэтому проходы прекращаются.
                    log.debug("Перекладка варианта {} невозможна: обхода зон нет", variantId);
                    break;
                }

                CalculationVariant relaid = applyDepth(rebuilt, scene, oksById,
                        rebuilt.getSummary().getUnconnectedOksIds(), crossings, depthUnresolved);
                List<UtilityCrossing> relaidCrossings = crossings.get(variantId);
                long violations = unmetClearances(relaidCrossings);
                boolean noOksLost = relaid.getSummary().getUnconnectedOksIds().size()
                        <= allowedUnconnected;

                log.debug("Перекладка {}, проход {}: зон {}, трасса {}, нарушений {} → {}",
                        variantId, pass + 1, zones.size(),
                        relaid.getStructureFingerprint().equals(variant.getStructureFingerprint())
                                ? "та же" : "другая",
                        bestViolations, violations);

                if (violations < bestViolations && noOksLost) {
                    log.info("Вариант {} переложен по результатам профиля: нарушений просвета "
                                    + "{} → {}, S {} → {}", variantId, bestViolations, violations,
                            String.format("%.3f", bestVariant.getSummary().getScore()),
                            String.format("%.3f", relaid.getSummary().getScore()));
                    bestVariant = relaid;
                    bestCrossings = relaidCrossings;
                    bestUnresolved = depthUnresolved.get(variantId);
                    bestViolations = violations;
                }

                // Следующий проход строже: к прежним зонам добавляются те, что остались
                // после перекладки. Нарушение ровно там, где уже стоит запрет, новой
                // зоны не даёт — повторять построение с теми же запретами незачем,
                // трасса получится та же.
                int added = 0;
                for (Geometry zone : forbiddenZones(relaidCrossings)) {
                    boolean known = zones.stream().anyMatch(existing -> existing.covers(zone));
                    if (!known) {
                        zones.add(zone);
                        added++;
                    }
                }
                if (added == 0) {
                    break;
                }
            }

            if (bestVariant == variant) {
                log.debug("Перекладка варианта {} не помогла: нарушений просвета осталось {}",
                        variantId, bestViolations);
            }
            crossings.put(variantId, bestCrossings);
            depthUnresolved.put(variantId, bestUnresolved);
            out.add(bestVariant);
        }
        return out;
    }

    /**
     * Зоны запрета вокруг пересечений, где просвет не выдержан.
     * <p>
     * Радиус считается по недостающему просвету, а не по построенной глубине: глубина
     * в таком месте как раз и осталась обычной, потому что манёвр не поместился, и по
     * ней разбег вышел бы нулевым. Не хватило ровно {@code required − actual} метров
     * по вертикали; при предельном уклоне на них нужно {@code (required − actual) /
     * maxSlope} метров хода плюс половина горизонтальной вставки в зоне пересечения
     * (раздел 4 приложения по глубине). Столько места и должно быть до пересечения —
     * значит, пересечь коммуникацию нужно не ближе этого расстояния.
     */
    private List<Geometry> forbiddenZones(List<UtilityCrossing> crossings) {
        ReferenceProperties.Depth params = catalog.props().getDepth();
        List<Geometry> zones = new ArrayList<>();
        for (UtilityCrossing c : crossings) {
            if (c.getRequiredClearance() <= 0
                    || c.getActualClearance() + 1e-6 >= c.getRequiredClearance()) {
                continue;
            }
            double shortfall = c.getRequiredClearance() - c.getActualClearance();
            double runup = shortfall / params.getMaxSlope() + params.getCrossingFlatHalf();
            zones.add(Geo.point(c.getLocation()).buffer(runup));
        }
        return zones;
    }

    /** Сколько пересечений осталось без требуемого вертикального просвета. */
    private long unmetClearances(List<UtilityCrossing> crossings) {
        if (crossings == null) {
            return 0;
        }
        return crossings.stream()
                .filter(c -> c.getRequiredClearance() > 0)
                .filter(c -> c.getActualClearance() + 1e-6 < c.getRequiredClearance())
                .count();
    }

    /**
     * Достройка вариантов затравочными терминалами.
     * <p>
     * Запускается, только если разбиения ОКС дали меньше трёх содержательно разных
     * решений: на конкурсном наборе их три и этот код не работает. На наборе, где
     * все ОКС лежат одной гроздью, разбиение единственное, и «до трёх вариантов»
     * раздела 2.11 ТЗ иначе не выполнить. Эвристика запускается с другого
     * затравочного терминала и строит при той же точке врезки другую сеть.
     */
    private int addSeededVariants(List<CalculationVariant> produced,
                                  Map<String, List<List<SteinerTreeBuilder.Terminal>>>
                                          partitionByVariant,
                                  List<List<List<SteinerTreeBuilder.Terminal>>> partitions,
                                  InputScene scene,
                                  RoutingGraph graph,
                                  ObstacleField field,
                                  List<TieInCandidate> candidates,
                                  Map<String, FutureOks> oksById,
                                  Map<String, String> terminalNodeIds,
                                  Map<String, double[]> distanceFromTerminal,
                                  int variantCounter,
                                  RouteBarrier userZones,
                                  List<String> outsideGraph) {
        Set<String> bases = produced.stream()
                .map(VariantPlanner::baseOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (bases.size() >= MAX_VARIANTS || partitions.isEmpty()) {
            return variantCounter;
        }

        Set<String> seen = produced.stream()
                .map(CalculationVariant::getStructureFingerprint)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        int attempts = Math.max(0, routingProps.getSeededVariantAttempts());
        // Затравка не должна стоить подключения: вариант с лишним неподключенным ОКС
        // дороже любого из уже найденных на 100 млн руб. штрафа (раздел 8.3 ТП),
        // и в выдаче ему делать нечего.
        int bestUnconnected = produced.stream()
                .mapToInt(v -> v.getSummary().getUnconnectedOksIds().size())
                .min().orElse(0);
        int added = 0;

        for (int seed = 1; seed <= attempts && seen.size() < MAX_VARIANTS; seed++) {
            String variantId = "v" + (++variantCounter);
            CalculationVariant seeded = buildVariant(scene, graph, field, candidates,
                    partitions.get(0), oksById, terminalNodeIds, distanceFromTerminal,
                    variantId, userZones, seed, outsideGraph);
            if (seeded == null
                    || seeded.getSummary().getUnconnectedOksIds().size() > bestUnconnected
                    || !seen.add(seeded.getStructureFingerprint())) {
                continue;
            }
            produced.add(seeded);
            partitionByVariant.put(variantId, partitions.get(0));
            added++;
        }
        if (added > 0) {
            log.info("Разбиения дали {} решений; затравками достроено ещё {}",
                    bases.size(), added);
        }
        return variantCounter;
    }

    /**
     * Проверка клиренса для отрезка, которого нет в графе видимости.
     * <p>
     * Диаметр приходит от вызывающего: отвод к одному объекту проверяется по своей
     * трубе, перенос развилки — по трубе примыкающей ветви. Требовать везде габарит
     * магистрали значит закрывать проходы, в которые труба проходит.
     */
    private static SteinerTreeBuilder.Passability clearanceOf(ObstacleField field,
                                                              RouteBarrier barrier) {
        return (from, to, exemptOks, requiredDn) ->
                field.isPassable(from, to, requiredDn, exemptOks) && !barrier.blocks(from, to);
    }

    /** Первое положительное из перечисленного — так задаётся порядок источников. */
    private static int firstPositive(int... values) {
        for (int value : values) {
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    /** Основная часть отпечатка: точки врезки и разбиение ОКС, без формы дерева. */
    private static String baseOf(CalculationVariant v) {
        String fingerprint = v.getStructureFingerprint();
        int tail = fingerprint.lastIndexOf('|');
        return tail < 0 ? fingerprint : fingerprint.substring(0, tail);
    }

    /**
     * Форма сети одной строкой: концы участков, округлённые до метра. Две сети с одними
     * и теми же точками врезки различаются именно ходом трасс, а метр — та точность,
     * ниже которой различие уже «небольшое смещение» из раздела 2.8 ТЗ.
     */
    private static String topologySignature(List<NewSegment> segments) {
        return Integer.toHexString(segments.stream()
                .map(seg -> {
                    Coordinate a = seg.getGeometry().getCoordinateN(0);
                    Coordinate b = seg.getGeometry().getCoordinateN(
                            seg.getGeometry().getNumPoints() - 1);
                    return Math.round(a.x) + "," + Math.round(a.y) + ">"
                            + Math.round(b.x) + "," + Math.round(b.y);
                })
                .sorted()
                .collect(Collectors.joining(";"))
                .hashCode());
    }

    /**
     * Отсев содержательно совпадающих решений и ранжирование по S.
     * <p>
     * Раздел 2.8 ТЗ: небольшое смещение одной и той же трассы отдельным вариантом
     * не считается, поэтому первым проходом берётся по одному решению на каждую пару
     * «точки врезки + разбиение ОКС». Если таких пар меньше трёх, вторым проходом
     * добираются сети другой формы — это всё же разные решения, а пустое место
     * в выдаче не помогает никому.
     */
    private List<CalculationVariant> rank(List<CalculationVariant> variants,
                                          Map<String, Long> depthViolations) {
        // Показатель S считается строго по разделу 8.2 ТП и ничем не дополняется.
        // Невыдержанный вертикальный просвет — не надбавка к стоимости, а признак
        // того, что вариант неисполним, поэтому он отсекает раньше сравнения по S.
        Comparator<CalculationVariant> order = Comparator
                .comparingLong((CalculationVariant v) ->
                        depthViolations.getOrDefault(v.getVariantId(), 0L))
                .thenComparingDouble(v -> v.getSummary().getScore());

        List<CalculationVariant> pool = new ArrayList<>(variants);
        pool.sort(order);

        List<CalculationVariant> out = new ArrayList<>();
        Set<String> takenBases = new LinkedHashSet<>();
        for (CalculationVariant v : pool) {
            if (out.size() >= MAX_VARIANTS) {
                break;
            }
            if (takenBases.add(baseOf(v))) {
                out.add(v);
            }
        }
        Set<String> takenShapes = out.stream()
                .map(CalculationVariant::getStructureFingerprint)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (CalculationVariant v : pool) {
            if (out.size() >= MAX_VARIANTS) {
                break;
            }
            if (takenShapes.add(v.getStructureFingerprint())) {
                out.add(v);
            }
        }

        out.sort(order);
        List<CalculationVariant> ranked = new ArrayList<>(out.size());
        for (int i = 0; i < out.size(); i++) {
            CalculationVariant v = out.get(i);
            ranked.add(v.withSummary(v.getSummary().withRank(i + 1)));
        }
        return ranked;
    }

    /**
     * Сколько пересечений в варианте осталось без требуемого вертикального просвета.
     * <p>
     * Такое бывает, когда пересечение приходится на первые метры нитки: на смену
     * глубины с 3,0 до 2,0 м при уклоне не круче 0,10 м/м нужно десять метров разбега,
     * а до узла ветвления или точки врезки их нет. Сервис не подменяет глубину желаемой,
     * а показывает построенное и понижает такой вариант.
     */
    private Map<String, Long> depthViolations(Map<String, List<UtilityCrossing>> crossings) {
        Map<String, Long> out = new LinkedHashMap<>();
        crossings.forEach((variantId, list) -> out.put(variantId, list.stream()
                .filter(c -> c.getRequiredClearance() > 0)
                .filter(c -> c.getActualClearance() + 1e-6 < c.getRequiredClearance())
                .count()));
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
