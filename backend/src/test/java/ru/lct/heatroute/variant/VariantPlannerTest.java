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
import ru.lct.heatroute.domain.result.TieInResult;
import ru.lct.heatroute.domain.result.VariantSummary;
import ru.lct.heatroute.ingest.GeoJsonStreamParser;
import ru.lct.heatroute.ingest.SceneAssembler;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Полный расчёт на конкурсном наборе: от разобранного GeoJSON до ранжированных вариантов.
 * <p>
 * Тест проверяет инварианты, которые эксперты проверяют первыми: все точки подключения
 * обслужены, условные диаметры соответствуют расходам, предельные длины соблюдены,
 * степень камеры не превышает четырёх, стоимость сходится с суммой объектов, показатель
 * ранжирования считается по формуле раздела 6 ТП.
 * <p>
 * Состав стоимости с редакции приложения от 18.09 короче: новые участки, новые камеры
 * и врезки в существующие камеры. Реконструкции существующей сети в расчётной модели
 * нет, поэтому и в протяжённость показателя входит только новая сеть.
 */
@SpringBootTest
@ActiveProfiles("nodb")
class VariantPlannerTest {

    @Autowired
    GeoJsonStreamParser parser;
    @Autowired
    SceneAssembler assembler;
    @Autowired
    VariantPlanner planner;
    @Autowired
    ReferenceCatalog catalog;
    @Autowired
    ru.lct.heatroute.routing.RoutingProperties routingProps;

    private static VariantPlanner.Plan plan;
    private static InputScene scene;

    private VariantPlanner.Plan plan() throws Exception {
        if (plan == null) {
            SceneAssembler.Collector collector = new SceneAssembler.Collector();
            try (InputStream in = getClass().getResourceAsStream("/samples/dataset_lct2026.geojson")) {
                parser.parse(Objects.requireNonNull(in), collector::accept);
            }
            scene = assembler.assemble(collector);
            plan = planner.plan(scene);
            report();
        }
        return plan;
    }

    private void report() {
        System.out.println();
        System.out.println("==================== РЕЗУЛЬТАТ РАСЧЁТА ====================");
        System.out.printf("Расчётный ДУ клиренсов: %d мм | граф: %d узлов, %d рёбер | "
                        + "кандидатов врезки: %d | время: %d мс%n",
                plan.getDesignDiameter(), plan.getGraphNodes(), plan.getGraphEdges(),
                plan.getTieInCandidates(), plan.getMillis());
        for (CalculationVariant v : plan.getVariants()) {
            VariantSummary s = v.getSummary();
            System.out.println("----------------------------------------------------------");
            System.out.printf("Вариант %s (место %d): %s%n", v.getVariantId(), s.getRank(),
                    v.getDescription());
            System.out.printf("  участков %d, камер %d, тех. узлов %d, "
                            + "присоединений %d, врезок в существующие камеры %d%n",
                    v.getSegments().size(), v.getChambers().size(), v.getTechnicalNodes().size(),
                    v.getTieIns().size(), s.getExistingChamberTieInCount());
            System.out.printf("  новая сеть %.1f м%n", s.getNewNetworkLength());
            System.out.printf("  линейные %,.0f + камеры %,.0f + врезки %,.0f = "
                            + "строительство %,.0f; штраф %,.0f; итого %,.0f руб.%n",
                    s.getConstructionCost() - s.getChamberConstructionCost()
                            - s.getExistingChamberTieInCost(),
                    s.getChamberConstructionCost(), s.getExistingChamberTieInCost(),
                    s.getConstructionCost(), s.getUnconnectedPenalty(), s.getCalculatedCost());
            System.out.printf("  S = %.3f | не подключено точек: %s%n",
                    s.getScore(), s.getUnconnectedOksIds());
        }
        System.out.println("==========================================================");
        System.out.println();
    }

    @Test
    @DisplayName("Расчёт даёт от одного до трёх ранжированных вариантов")
    void producesRankedVariants() throws Exception {
        VariantPlanner.Plan p = plan();
        assertThat(p.getVariants()).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        for (int i = 0; i < p.getVariants().size(); i++) {
            assertThat(p.getVariants().get(i).getSummary().getRank()).isEqualTo(i + 1);
        }
        // Ранжирование по возрастанию S: меньше — выше.
        for (int i = 1; i < p.getVariants().size(); i++) {
            assertThat(p.getVariants().get(i).getSummary().getScore())
                    .isGreaterThanOrEqualTo(p.getVariants().get(i - 1).getSummary().getScore());
        }
    }

    @Test
    @DisplayName("Все перспективные ОКС подключены")
    void connectsEveryOks() throws Exception {
        VariantPlanner.Plan p = plan();
        CalculationVariant best = p.getVariants().get(0);
        assertThat(best.getSummary().getUnconnectedOksIds()).isEmpty();
        assertThat(best.getSummary().getUnconnectedPenalty()).isZero();
    }

    @Test
    @DisplayName("Условный диаметр каждого участка минимально достаточен для его расхода")
    void diametersMatchFlows() throws Exception {
        VariantPlanner.Plan p = plan();
        for (CalculationVariant v : p.getVariants()) {
            for (NewSegment s : v.getSegments()) {
                int required = catalog.selectForFlow(s.getFlowTph()).orElseThrow().getDn();
                assertThat(s.getDiameter())
                        .as("участок %s: расход %.2f т/ч", s.getId(), s.getFlowTph())
                        .isGreaterThanOrEqualTo(required);
                assertThat(catalog.byDnOrNextUp(s.getDiameter()).getCapacityTph())
                        .isGreaterThanOrEqualTo(s.getFlowTph());
            }
        }
    }

    @Test
    @DisplayName("Стоимость участка сходится с длиной, ставкой и коэффициентом прохода")
    void segmentCostsAreConsistent() throws Exception {
        VariantPlanner.Plan p = plan();
        for (CalculationVariant v : p.getVariants()) {
            for (NewSegment s : v.getSegments()) {
                double expected = s.getLength() * catalog.newCostPerM(s.getDiameter())
                        * s.getKSpecial() * s.getKDepth();
                assertThat(s.getCost())
                        .as("стоимость участка %s", s.getId())
                        .isCloseTo(expected, org.assertj.core.data.Percentage.withPercentage(1));
            }
        }
    }

    @Test
    @DisplayName("Итоговая стоимость и показатель S сходятся с составляющими")
    void summaryIsConsistent() throws Exception {
        VariantPlanner.Plan p = plan();
        for (CalculationVariant v : p.getVariants()) {
            VariantSummary s = v.getSummary();

            // Раздел 6: стоимость строительства — это ровно три слагаемых: участки,
            // новые камеры и врезки в существующие камеры.
            double segmentCost = v.getSegments().stream()
                    .mapToDouble(NewSegment::getCost).sum();
            assertThat(s.getConstructionCost())
                    .as("стоимость строительства варианта %s", v.getVariantId())
                    .isCloseTo(segmentCost + s.getChamberConstructionCost()
                                    + s.getExistingChamberTieInCost(),
                            org.assertj.core.data.Offset.offset(2.0));

            assertThat(s.getCalculatedCost())
                    .as("итоговая стоимость варианта %s", v.getVariantId())
                    .isCloseTo(s.getConstructionCost() + s.getUnconnectedPenalty(),
                            org.assertj.core.data.Offset.offset(2.0));

            // Протяжённость показателя — только новая сеть: реконструкции в модели нет.
            assertThat(s.getNewNetworkLength()).isCloseTo(
                    v.getSegments().stream().mapToDouble(NewSegment::getLength).sum(),
                    org.assertj.core.data.Offset.offset(0.05));

            double expectedScore =
                    catalog.score(s.getCalculatedCost(), s.getNewNetworkLength());
            assertThat(s.getScore()).isCloseTo(expectedScore,
                    org.assertj.core.data.Offset.offset(0.002));
        }
    }

    @Test
    @DisplayName("Врезки в сводке равны сумме врезок по местам присоединения")
    void tieInsAreAccounted() throws Exception {
        VariantPlanner.Plan p = plan();
        for (CalculationVariant v : p.getVariants()) {
            assertThat(v.getTieIns()).isNotEmpty();
            for (TieInResult t : v.getTieIns()) {
                // Раздел 3.2: цена одной врезки фиксирована, и платится она за каждый
                // новый участок, заканчивающийся в существующей камере.
                assertThat(t.getCost())
                        .as("стоимость врезок присоединения %s", t.getId())
                        .isEqualTo(t.getTieInCount() * catalog.tieInCost());
            }

            int count = v.getTieIns().stream().mapToInt(TieInResult::getTieInCount).sum();
            assertThat(v.getSummary().getExistingChamberTieInCount())
                    .as("количество врезок в сводке варианта %s", v.getVariantId())
                    .isEqualTo(count);
            assertThat(v.getSummary().getExistingChamberTieInCost())
                    .isEqualTo(count * catalog.tieInCost());
        }
    }

    @Test
    @DisplayName("К каждому узлу сети примыкает не более четырёх участков")
    void chamberDegreeWithinLimit() throws Exception {
        VariantPlanner.Plan p = plan();
        int max = catalog.props().getMaxChamberDegree();

        for (CalculationVariant v : p.getVariants()) {
            assertThat(v.getChambers())
                    .allMatch(ch -> ch.getDegree() <= max,
                            "степень новой камеры не больше " + max);

            // Проверка по фактической выгрузке, а не по служебному полю: считаем,
            // сколько участков ссылается на каждый узел как на свой конец.
            java.util.Map<String, Integer> degree = new java.util.LinkedHashMap<>();
            for (NewSegment s : v.getSegments()) {
                degree.merge(s.getStartNodeId(), 1, Integer::sum);
                degree.merge(s.getEndNodeId(), 1, Integer::sum);
            }
            // К узлу врезки дополнительно примыкает существующая сеть: для врезки
            // в существующую камеру — её текущие участки, для новой камеры на участке —
            // две половины разрезанного участка.
            for (TieInResult t : v.getTieIns()) {
                int fromNew = v.getSegments().stream()
                        .filter(s -> s.getStartNodeId().equals(nodeIdOf(v, t))
                                || s.getEndNodeId().equals(nodeIdOf(v, t)))
                        .mapToInt(s -> 1).sum();
                int occupied = "heat_chamber".equals(t.getExistingObjectType())
                        ? existingDegree(t) : 2;
                assertThat(fromNew + occupied)
                        .as("примыканий к узлу врезки %s", t.getExistingObjectId())
                        .isLessThanOrEqualTo(max);
            }

            degree.forEach((nodeId, d) -> assertThat(d)
                    .as("примыканий к узлу %s", nodeId)
                    .isLessThanOrEqualTo(max));
        }
    }

    /** Идентификатор узла в точке врезки: существующая камера либо новая камера. */
    private String nodeIdOf(CalculationVariant v, TieInResult t) {
        if ("heat_chamber".equals(t.getExistingObjectType())) {
            return t.getExistingObjectId();
        }
        return v.getChambers().stream()
                .filter(ch -> ch.getLocation().getCoordinate()
                        .distance(t.getLocation().getCoordinate()) < 0.5)
                .map(ch -> ch.getId())
                .findFirst().orElse("");
    }

    private int existingDegree(TieInResult t) {
        return scene.getChambers().stream()
                .filter(ch -> ch.getId().equals(t.getExistingObjectId()))
                .mapToInt(ch -> ch.getExistingDegree())
                .findFirst().orElse(0);
    }

    @Test
    @DisplayName("Ни один участок сам по себе не длиннее предела своего ДУ")
    void runLengthLimitRespected() throws Exception {
        VariantPlanner.Plan p = plan();
        for (CalculationVariant v : p.getVariants()) {
            // Дешёвая локальная проверка: отдельный участок уже не должен выходить
            // за предел таблицы 1. Предел по каждому пути от места присоединения
            // до точки подключения проверяет NetworkSanityTest.
            for (NewSegment s : v.getSegments()) {
                assertThat(s.getLength())
                        .as("участок %s длиной %.1f м при ДУ %d (предел %.0f м)",
                                s.getId(), s.getLength(), s.getDiameter(),
                                catalog.maxRunLength(s.getDiameter()))
                        .isLessThanOrEqualTo(catalog.maxRunLength(s.getDiameter()) + 0.5);
            }
        }
    }

    @Test
    @DisplayName("Расход, вносимый в существующую сеть, равен подключённой нагрузке")
    void addedFlowMatchesConnectedLoad() throws Exception {
        VariantPlanner.Plan p = plan();
        for (CalculationVariant v : p.getVariants()) {
            if (!v.getSummary().getUnconnectedPointKeys().isEmpty()) {
                // Часть нагрузки не подключена, сходиться с полной суммой она не обязана.
                continue;
            }
            // Вносимый расход читается по участкам, выходящим из мест присоединения:
            // это узлы, в которые не входит ни один участок. Так проверяется то, что
            // уедет в выгрузку, а не отдельно посчитанная служебная величина.
            java.util.Set<String> ends = v.getSegments().stream()
                    .map(NewSegment::getEndNodeId)
                    .collect(java.util.stream.Collectors.toSet());
            double addedTotal = v.getSegments().stream()
                    .filter(s -> !ends.contains(s.getStartNodeId()))
                    .mapToDouble(NewSegment::getFlowTph).sum();
            assertThat(addedTotal)
                    .as("вариант %s: суммарный вносимый расход равен подключённой нагрузке",
                            v.getVariantId())
                    .isCloseTo(scene.totalFutureFlowTph(),
                            org.assertj.core.data.Offset.offset(0.05));
        }
    }

    @Test
    @DisplayName("Предел времени на вариант сужает перебор и не теряет объекты")
    void timeBudgetNarrowsSearchWithoutLosingObjects() throws Exception {
        VariantPlanner.Plan unlimited = plan();

        // Предел задаётся настройкой сервиса, и подменить её иначе как на общем бине
        // нельзя. Расчёты в сюите идут по одному, а значение возвращается на место
        // в любом случае: соседний тест не должен зависеть от этого.
        long before = routingProps.getVariantTimeBudgetSeconds();
        VariantPlanner.Plan tight;
        try {
            routingProps.setVariantTimeBudgetSeconds(1);
            tight = planner.plan(scene);
        } finally {
            routingProps.setVariantTimeBudgetSeconds(before);
        }

        assertThat(tight.getVariants())
                .as("предел сужает перебор, а не отменяет расчёт")
                .isNotEmpty();
        assertThat(tight.getMillis())
                .as("секунда на вариант против %d мс без предела", unlimited.getMillis())
                .isLessThan(unlimited.getMillis());

        // Сужение перебора может дать решение хуже, но не имеет права потерять объект:
        // каждый перспективный ОКС либо в построенной сети, либо назван неподключённым.
        CalculationVariant best = tight.getVariants().get(0);
        java.util.Set<String> touched = new java.util.LinkedHashSet<>();
        best.getSegments().forEach(s -> {
            touched.add(s.getStartNodeId());
            touched.add(s.getEndNodeId());
        });
        for (FutureOks oks : scene.getFutureOks()) {
            assertThat(touched.contains(oks.getConnectionPointId())
                    || best.getSummary().getUnconnectedPointKeys().contains(oks.getId()))
                    .as("ОКС %s пропал при сужении перебора", oks.getId())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Варианты содержательно различны")
    void variantsAreDistinct() throws Exception {
        VariantPlanner.Plan p = plan();
        List<String> fingerprints = p.getVariants().stream()
                .map(CalculationVariant::getStructureFingerprint).collect(java.util.stream.Collectors.toList());
        assertThat(fingerprints).doesNotHaveDuplicates();

        // Отпечаток двухуровневый: «точки врезки + разбиение ОКС | форма дерева».
        // Форма — запасной источник различий на случай, когда разбиение единственное.
        // На конкурсном наборе он не нужен: все три варианта различаются по существу,
        // то есть точками врезки и составом частей сети (раздел 2.8 ТЗ).
        List<String> bases = fingerprints.stream()
                .map(f -> f.substring(0, f.lastIndexOf('|')))
                .collect(java.util.stream.Collectors.toList());
        assertThat(bases)
                .as("варианты отличаются точками врезки и разбиением, а не только формой трассы")
                .doesNotHaveDuplicates();
    }
}
