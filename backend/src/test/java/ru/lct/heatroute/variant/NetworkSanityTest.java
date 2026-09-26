package ru.lct.heatroute.variant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatroute.domain.model.FutureOks;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.domain.result.CalculationVariant;
import ru.lct.heatroute.domain.result.NewSegment;
import ru.lct.heatroute.ingest.GeoJsonStreamParser;
import ru.lct.heatroute.ingest.SceneAssembler;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Инженерная осмысленность построенной сети.
 * <p>
 * Тесты этого класса ловят решения, которые эксперт назовёт ошибкой проектирования:
 * труба, ведущая в никуда; участок с нулевым расходом; ветвь, в которой диаметр растёт
 * по мере удаления от места присоединения; путь, выходящий за предельную длину своего
 * условного диаметра.
 * <p>
 * Здесь же собраны инварианты, которые редакция приложения от 18.09 сделала
 * обязательными: монотонность условного диаметра по направлению от точки подключения
 * к месту присоединения, предельная длина по каждому пути отдельно и правило оплаты
 * врезок. Все они проверяются по выгруженному результату на конкурсном наборе —
 * так же, как это сделает проверяющий.
 */
@SpringBootTest
@ActiveProfiles("nodb")
class NetworkSanityTest {

    @Autowired
    GeoJsonStreamParser parser;
    @Autowired
    SceneAssembler assembler;
    @Autowired
    VariantPlanner planner;
    @Autowired
    ReferenceCatalog catalog;

    private static InputScene scene;
    private static VariantPlanner.Plan plan;

    private VariantPlanner.Plan plan() throws Exception {
        if (plan == null) {
            SceneAssembler.Collector collector = new SceneAssembler.Collector();
            try (InputStream in = getClass().getResourceAsStream("/samples/dataset_lct2026.geojson")) {
                parser.parse(Objects.requireNonNull(in), collector::accept);
            }
            scene = assembler.assemble(collector);
            plan = planner.plan(scene);
        }
        return plan;
    }

    @Test
    @DisplayName("Ни один участок не построен вхолостую: расход везде положительный")
    void everySegmentCarriesFlow() throws Exception {
        VariantPlanner.Plan p = plan();
        double minOksFlow = scene.getFutureOks().stream()
                .mapToDouble(FutureOks::getFlowTph).min().orElse(0);

        for (CalculationVariant v : p.getVariants()) {
            for (NewSegment s : v.getSegments()) {
                assertThat(s.getFlowTph())
                        .as("участок %s длиной %.1f м с расходом %.2f т/ч — труба в никуда",
                                s.getId(), s.getLength(), s.getFlowTph())
                        .isGreaterThanOrEqualTo(minOksFlow - 1e-6);
            }
        }
    }

    @Test
    @DisplayName("Каждая ветвь заканчивается точкой подключения, а не тупиком")
    void everyBranchEndsAtConsumer() throws Exception {
        VariantPlanner.Plan p = plan();
        Set<String> connectionPoints = new LinkedHashSet<>();
        scene.getFutureOks().forEach(o -> connectionPoints.add(o.getConnectionPointId()));

        for (CalculationVariant v : p.getVariants()) {
            // Узел является концом ветви, если на него ссылается ровно один участок.
            Map<String, Integer> degree = new LinkedHashMap<>();
            for (NewSegment s : v.getSegments()) {
                degree.merge(s.getStartNodeId(), 1, Integer::sum);
                degree.merge(s.getEndNodeId(), 1, Integer::sum);
            }
            Set<String> tieInNodes = new LinkedHashSet<>();
            v.getTieIns().forEach(t -> tieInNodes.add(t.getExistingObjectId()));
            v.getChambers().forEach(c -> tieInNodes.add(c.getId()));

            List<String> deadEnds = new ArrayList<>();
            degree.forEach((node, d) -> {
                if (d != 1) {
                    return;
                }
                // Конец ветви допустим только в точке подключения ОКС.
                if (!connectionPoints.contains(node)) {
                    deadEnds.add(node);
                }
            });

            // Узел врезки тоже имеет степень один, если от него отходит одна ветвь.
            deadEnds.removeIf(tieInNodes::contains);

            assertThat(deadEnds)
                    .as("вариант %s: концы ветвей вне точек подключения", v.getVariantId())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("От точки подключения к месту присоединения условный диаметр не уменьшается")
    void diameterDoesNotDecreaseTowardsTieIn() throws Exception {
        VariantPlanner.Plan p = plan();

        for (CalculationVariant v : p.getVariants()) {
            // Дерево восстанавливается по ссылкам участков: корни — места присоединения,
            // участки направлены от них к точкам подключения.
            Map<String, List<NewSegment>> outgoing = outgoing(v);

            for (NewSegment nearer : v.getSegments()) {
                for (NewSegment further
                        : outgoing.getOrDefault(nearer.getEndNodeId(), List.of())) {
                    // Раздел 2.3 в редакции от 18.09 формулирует это как безусловный
                    // инвариант: исключений по расходу у него нет. Идя от потребителя
                    // к месту присоединения, диаметр только растёт или остаётся прежним.
                    assertThat(further.getDiameter())
                            .as("участок %s (ДУ %d, расход %.2f) удалён от места присоединения "
                                            + "дальше, чем %s (ДУ %d, расход %.2f), "
                                            + "но толще него",
                                    further.getId(), further.getDiameter(),
                                    further.getFlowTph(), nearer.getId(),
                                    nearer.getDiameter(), nearer.getFlowTph())
                            .isLessThanOrEqualTo(nearer.getDiameter());
                }
            }
        }
    }

    @Test
    @DisplayName("Предельная длина выдержана на каждом пути от присоединения до потребителя")
    void runLengthLimitOnEveryPath() throws Exception {
        VariantPlanner.Plan p = plan();

        for (CalculationVariant v : p.getVariants()) {
            Map<String, List<NewSegment>> outgoing = outgoing(v);
            List<List<NewSegment>> paths = new ArrayList<>();
            for (String root : roots(v, outgoing)) {
                collectPaths(outgoing, root, new ArrayList<>(), paths);
            }

            assertThat(paths)
                    .as("вариант %s: путей от мест присоединения не нашлось вовсе",
                            v.getVariantId())
                    .isNotEmpty();

            // Раздел 2.3: предел проверяется по каждому непрерывному пути отдельно.
            // Общий ствол входит в каждый путь — он и получается заново пройденным
            // при переборе путей; длины параллельных ветвей между собой не
            // складываются, потому что каждый путь считается сам по себе.
            // Камера и технический узел отсчёт не прерывают, смена ДУ — прерывает.
            for (List<NewSegment> path : paths) {
                double run = 0;
                int currentDn = 0;
                for (NewSegment segment : path) {
                    run = segment.getDiameter() == currentDn ? run + segment.getLength()
                            : segment.getLength();
                    currentDn = segment.getDiameter();

                    double limit = catalog.maxRunLength(currentDn);
                    assertThat(run)
                            .as("вариант %s, путь до %s: непрерывная часть ДУ %d достигла "
                                            + "%.1f м при пределе %.0f м (участок %s)",
                                    v.getVariantId(),
                                    path.get(path.size() - 1).getEndNodeId(),
                                    currentDn, run, limit, segment.getId())
                            .isLessThanOrEqualTo(limit + 0.5);
                }
            }
        }
    }

    @Test
    @DisplayName("Врезка стоит 5 млн за участок в существующей камере и ноль при новой")
    void tieInCostFollowsExistingChamberRule() throws Exception {
        VariantPlanner.Plan p = plan();

        for (CalculationVariant v : p.getVariants()) {
            for (ru.lct.heatroute.domain.result.TieInResult t : v.getTieIns()) {
                if ("heat_chamber".equals(t.getExistingObjectType())) {
                    // Считаем по выгруженным участкам, а не по служебному полю: врезка
                    // по разделу 3.2 — это каждый новый линейный участок, геометрически
                    // заканчивающийся в существующей камере.
                    long touching = v.getSegments().stream()
                            .filter(s -> t.getExistingObjectId().equals(s.getStartNodeId())
                                    || t.getExistingObjectId().equals(s.getEndNodeId()))
                            .count();
                    assertThat(touching)
                            .as("к существующей камере %s должен примыкать хотя бы один "
                                            + "новый участок, иначе это не присоединение",
                                    t.getExistingObjectId())
                            .isPositive();
                    assertThat(t.getTieInCount())
                            .as("присоединение %s к существующей камере %s",
                                    t.getId(), t.getExistingObjectId())
                            .isEqualTo((int) touching);
                    assertThat(t.getCost())
                            .as("стоимость врезок присоединения %s", t.getId())
                            .isEqualTo(touching * catalog.tieInCost());
                } else {
                    // В месте присоединения строится новая камера: присоединение уже
                    // входит в её стоимость, отдельной врезки не начисляется.
                    assertThat(t.getTieInCount())
                            .as("присоединение %s выполнено новой камерой", t.getId())
                            .isZero();
                    assertThat(t.getCost()).isZero();
                }
            }
        }
    }

    @Test
    @DisplayName("Сеть связна: от каждой точки подключения есть путь к своей врезке")
    void networkIsConnected() throws Exception {
        VariantPlanner.Plan p = plan();

        for (CalculationVariant v : p.getVariants()) {
            Map<String, List<String>> adjacency = new LinkedHashMap<>();
            for (NewSegment s : v.getSegments()) {
                adjacency.computeIfAbsent(s.getStartNodeId(), k -> new ArrayList<>())
                        .add(s.getEndNodeId());
                adjacency.computeIfAbsent(s.getEndNodeId(), k -> new ArrayList<>())
                        .add(s.getStartNodeId());
            }

            Set<String> reachable = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            v.getTieIns().forEach(t -> queue.add(t.getExistingObjectId()));
            v.getChambers().forEach(c -> queue.add(c.getId()));
            while (!queue.isEmpty()) {
                String node = queue.poll();
                if (!reachable.add(node)) {
                    continue;
                }
                queue.addAll(adjacency.getOrDefault(node, List.of()));
            }

            List<String> orphans = new ArrayList<>();
            for (FutureOks oks : scene.getFutureOks()) {
                // Сверяемся по внутренним ключам расчёта: в unconnectedOksIds лежат
                // идентификаторы в исходном типе входного файла, и на конкурсном
                // наборе это числа, а не строки.
                if (v.getSummary().getUnconnectedPointKeys().contains(oks.getId())) {
                    continue;
                }
                if (!reachable.contains(oks.getConnectionPointId())) {
                    orphans.add(oks.getConnectionPointId());
                }
            }
            assertThat(orphans)
                    .as("вариант %s: точки подключения без пути к врезке", v.getVariantId())
                    .isEmpty();
        }
    }

    /** Участки, исходящие из каждого узла: дерево направлено от места присоединения. */
    private static Map<String, List<NewSegment>> outgoing(CalculationVariant v) {
        Map<String, List<NewSegment>> outgoing = new LinkedHashMap<>();
        for (NewSegment s : v.getSegments()) {
            outgoing.computeIfAbsent(s.getStartNodeId(), k -> new ArrayList<>()).add(s);
        }
        return outgoing;
    }

    /** Узлы, в которые не входит ни один участок, — места присоединения к сети. */
    private static Set<String> roots(CalculationVariant v,
                                     Map<String, List<NewSegment>> outgoing) {
        Set<String> starts = new LinkedHashSet<>(outgoing.keySet());
        v.getSegments().forEach(s -> starts.remove(s.getEndNodeId()));
        return starts;
    }

    /**
     * Все пути от узла до концов ветвей, каждый — своим списком участков.
     * <p>
     * Простой путь не может быть длиннее числа участков: если оказался длиннее, в дереве
     * появился цикл. Обрыв здесь превращает зависание обхода в понятную ошибку.
     */
    private static void collectPaths(Map<String, List<NewSegment>> outgoing, String node,
                                     List<NewSegment> prefix, List<List<NewSegment>> out) {
        int edges = outgoing.values().stream().mapToInt(List::size).sum();
        if (prefix.size() > edges) {
            throw new AssertionError("В сети найден цикл: обход дошёл до узла " + node
                    + ", пройдя участков больше, чем есть в варианте");
        }
        List<NewSegment> next = outgoing.getOrDefault(node, List.of());
        if (next.isEmpty()) {
            if (!prefix.isEmpty()) {
                out.add(new ArrayList<>(prefix));
            }
            return;
        }
        for (NewSegment segment : next) {
            prefix.add(segment);
            collectPaths(outgoing, segment.getEndNodeId(), prefix, out);
            prefix.remove(prefix.size() - 1);
        }
    }
}
