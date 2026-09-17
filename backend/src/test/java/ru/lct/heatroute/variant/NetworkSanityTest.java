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
 * Тесты этого класса ловят не нарушения буквы технического приложения, а решения,
 * которые эксперт назовёт ошибкой проектирования, даже если формально правила
 * соблюдены: труба, ведущая в никуда; участок с нулевым расходом; ветвь, в которой
 * диаметр растёт по мере удаления от источника; предельная длина непрерывной части
 * одного диаметра.
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
    @DisplayName("По направлению к источнику условный диаметр не уменьшается")
    void diameterDoesNotDecreaseTowardsSource() throws Exception {
        VariantPlanner.Plan p = plan();

        for (CalculationVariant v : p.getVariants()) {
            // Дерево восстанавливается по ссылкам участков: у каждого узла один
            // участок к источнику и остальные от него.
            Map<String, List<NewSegment>> outgoing = new LinkedHashMap<>();
            for (NewSegment s : v.getSegments()) {
                outgoing.computeIfAbsent(s.getStartNodeId(), k -> new ArrayList<>()).add(s);
            }

            for (NewSegment upstream : v.getSegments()) {
                for (NewSegment downstream : outgoing.getOrDefault(upstream.getEndNodeId(), List.of())) {
                    // Расход вниз по течению не больше, значит и диаметр не должен расти.
                    if (downstream.getFlowTph() > upstream.getFlowTph() + 1e-6) {
                        continue;   // развилка с обратным направлением — пропускаем
                    }
                    assertThat(downstream.getDiameter())
                            .as("участок %s (ДУ %d, расход %.2f) идёт от %s (ДУ %d, расход %.2f): "
                                            + "диаметр растёт при удалении от источника",
                                    downstream.getId(), downstream.getDiameter(),
                                    downstream.getFlowTph(), upstream.getId(),
                                    upstream.getDiameter(), upstream.getFlowTph())
                            .isLessThanOrEqualTo(upstream.getDiameter());
                }
            }
        }
    }

    @Test
    @DisplayName("Предельная длина непрерывной части одного диаметра соблюдена")
    void continuousRunWithinLimit() throws Exception {
        VariantPlanner.Plan p = plan();

        for (CalculationVariant v : p.getVariants()) {
            Map<String, List<NewSegment>> outgoing = new LinkedHashMap<>();
            for (NewSegment s : v.getSegments()) {
                outgoing.computeIfAbsent(s.getStartNodeId(), k -> new ArrayList<>()).add(s);
            }
            Set<String> starts = new LinkedHashSet<>(outgoing.keySet());
            v.getSegments().forEach(s -> starts.remove(s.getEndNodeId()));

            // Обход от корней с накоплением длины непрерывной части одного диаметра.
            // Смена диаметра начинает новый отсчёт, камера — нет (раздел 3 ТП).
            for (String root : starts) {
                Deque<Object[]> stack = new ArrayDeque<>();
                outgoing.getOrDefault(root, List.of())
                        .forEach(s -> stack.push(new Object[]{s, 0d}));

                while (!stack.isEmpty()) {
                    Object[] frame = stack.pop();
                    NewSegment segment = (NewSegment) frame[0];
                    double carried = (Double) frame[1];

                    double run = carried + segment.getLength();
                    double limit = catalog.maxRunLength(segment.getDiameter());
                    assertThat(run)
                            .as("непрерывная часть ДУ %d достигла %.1f м при пределе %.0f м "
                                            + "(участок %s)",
                                    segment.getDiameter(), run, limit, segment.getId())
                            .isLessThanOrEqualTo(limit + 0.5);

                    for (NewSegment next : outgoing.getOrDefault(segment.getEndNodeId(), List.of())) {
                        double carryOn = next.getDiameter() == segment.getDiameter() ? run : 0;
                        stack.push(new Object[]{next, carryOn});
                    }
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
                if (v.getSummary().getUnconnectedOksIds().contains(oks.getId())) {
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
}
