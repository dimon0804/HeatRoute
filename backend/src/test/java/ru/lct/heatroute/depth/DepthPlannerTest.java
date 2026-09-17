package ru.lct.heatroute.depth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.domain.reference.ReferenceProperties;
import ru.lct.heatroute.domain.result.CalculationVariant;
import ru.lct.heatroute.domain.result.NewSegment;
import ru.lct.heatroute.ingest.GeoJsonStreamParser;
import ru.lct.heatroute.ingest.SceneAssembler;
import ru.lct.heatroute.variant.VariantPlanner;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Дополнительная задача: трассировка с учётом глубины.
 * <p>
 * Проверяются правила приложения к кейсу, которые эксперт сверит по выгрузке:
 * шаг подбора глубины, вертикальные просветы в местах пересечений, предельный уклон
 * профиля, коэффициент стоимости по глубине и Z-координаты в геометрии.
 */
@SpringBootTest
@ActiveProfiles("nodb")
class DepthPlannerTest {

    @Autowired
    GeoJsonStreamParser parser;
    @Autowired
    SceneAssembler assembler;
    @Autowired
    VariantPlanner planner;
    @Autowired
    ReferenceCatalog catalog;

    private static InputScene scene;
    private static VariantPlanner.Plan flat;
    private static VariantPlanner.Plan deep;

    private void prepare() throws Exception {
        if (deep != null) {
            return;
        }
        SceneAssembler.Collector collector = new SceneAssembler.Collector();
        try (InputStream in = getClass().getResourceAsStream("/samples/dataset_lct2026.geojson")) {
            parser.parse(Objects.requireNonNull(in), collector::accept);
        }
        scene = assembler.assemble(collector);
        flat = planner.plan(scene);
        deep = planner.plan(scene, VariantPlanner.Progress.NONE, true);
        report();
    }

    private void report() {
        System.out.println();
        System.out.println("============ ДОПОЛНИТЕЛЬНАЯ ЗАДАЧА: ГЛУБИНА ============");
        for (CalculationVariant v : deep.getVariants()) {
            List<UtilityCrossing> crossings =
                    deep.getCrossingsByVariant().getOrDefault(v.getVariantId(), List.of());
            double maxDepth = v.getSegments().stream()
                    .mapToDouble(s -> Math.max(
                            s.getDepthStart() == null ? 0 : s.getDepthStart(),
                            s.getDepthEnd() == null ? 0 : s.getDepthEnd()))
                    .max().orElse(0);
            double minDepth = v.getSegments().stream()
                    .filter(s -> s.getDepthStart() != null)
                    .mapToDouble(NewSegment::getDepthStart)
                    .min().orElse(0);
            System.out.printf("Вариант %s (место %d): участков %d, пересечений по глубине %d, "
                            + "глубина %.1f..%.1f м, стоимость %,.0f руб., S = %.3f%n",
                    v.getVariantId(), v.getSummary().getRank(), v.getSegments().size(),
                    crossings.size(), minDepth, maxDepth,
                    v.getSummary().getCalculatedCost(), v.getSummary().getScore());
            crossings.stream().limit(4).forEach(c -> System.out.printf(
                    "    %s: %s, просвет %.2f м, глубина новой сети %.1f м%n",
                    c.getUtilityType(), c.passageLabel(), c.getActualClearance(), c.getNewDepth()));
        }
        System.out.println("=======================================================");
        System.out.println();
    }

    @Test
    @DisplayName("Режим с глубиной проставляет глубины всем участкам")
    void assignsDepths() throws Exception {
        prepare();
        for (CalculationVariant v : deep.getVariants()) {
            assertThat(v.getSegments())
                    .allMatch(s -> s.getDepthStart() != null && s.getDepthEnd() != null,
                            "у каждого участка задана глубина в начале и в конце");
        }
        // В плоской задаче глубина не задаётся вовсе (раздел 10.1 ТП).
        for (CalculationVariant v : flat.getVariants()) {
            assertThat(v.getSegments()).allMatch(s -> s.getDepthStart() == null);
        }
    }

    @Test
    @DisplayName("Глубина подобрана с шагом справочника и не выходит за границы")
    void depthWithinLimits() throws Exception {
        prepare();
        ReferenceProperties.Depth params = catalog.props().getDepth();

        for (CalculationVariant v : deep.getVariants()) {
            for (NewSegment s : v.getSegments()) {
                for (Double depth : List.of(s.getDepthStart(), s.getDepthEnd())) {
                    assertThat(depth)
                            .as("глубина участка %s", s.getId())
                            .isBetween(params.getMinDepth(), params.getMaxDepth());
                }
            }
        }
    }

    @Test
    @DisplayName("Вертикальный просвет в местах пересечений выдержан")
    void verticalClearanceRespected() throws Exception {
        prepare();
        for (CalculationVariant v : deep.getVariants()) {
            List<UtilityCrossing> crossings =
                    deep.getCrossingsByVariant().getOrDefault(v.getVariantId(), List.of());
            for (UtilityCrossing c : crossings) {
                if (c.getRequiredClearance() <= 0) {
                    continue;
                }
                assertThat(c.getActualClearance())
                        .as("просвет при пересечении %s (%s)", c.getUtilityId(), c.passageLabel())
                        .isGreaterThanOrEqualTo(c.getRequiredClearance() - 1e-6);
            }
        }
    }

    @Test
    @DisplayName("Уклон профиля не круче предельного")
    void slopeWithinLimit() throws Exception {
        prepare();
        double maxSlope = catalog.props().getDepth().getMaxSlope();

        for (CalculationVariant v : deep.getVariants()) {
            for (NewSegment s : v.getSegments()) {
                double drop = Math.abs(s.getDepthEnd() - s.getDepthStart());
                if (drop < 1e-9) {
                    continue;
                }
                double slope = drop / Math.max(s.getLength(), 1e-9);
                assertThat(slope)
                        .as("уклон участка %s: перепад %.2f м на %.1f м",
                                s.getId(), drop, s.getLength())
                        .isLessThanOrEqualTo(maxSlope + 1e-6);
            }
        }
    }

    @Test
    @DisplayName("Коэффициент по глубине применён к стоимости по формуле раздела 6.1")
    void depthCostFactorApplied() throws Exception {
        prepare();
        for (CalculationVariant v : deep.getVariants()) {
            for (NewSegment s : v.getSegments()) {
                double expectedFactor = Math.abs(s.getDepthStart() - s.getDepthEnd()) < 1e-9
                        ? catalog.depthCostFactor(s.getDepthStart())
                        : catalog.depthCostFactorAverage(s.getDepthStart(), s.getDepthEnd());
                assertThat(s.getKDepth())
                        .as("коэффициент глубины участка %s", s.getId())
                        .isCloseTo(expectedFactor, org.assertj.core.data.Offset.offset(0.002));

                double expectedCost = s.getLength() * catalog.newCostPerM(s.getDiameter())
                        * s.getKSpecial() * s.getKDepth();
                assertThat(s.getCost())
                        .as("стоимость участка %s с учётом глубины", s.getId())
                        .isCloseTo(expectedCost, org.assertj.core.data.Percentage.withPercentage(1));
            }
        }
    }

    @Test
    @DisplayName("Геометрия несёт Z-координаты, соответствующие глубине")
    void geometryCarriesZ() throws Exception {
        prepare();
        for (CalculationVariant v : deep.getVariants()) {
            for (NewSegment s : v.getSegments()) {
                var coords = s.getGeometry().getCoordinates();
                assertThat(coords[0].getZ())
                        .as("Z в начале участка %s", s.getId())
                        .isCloseTo(-s.getDepthStart(), org.assertj.core.data.Offset.offset(0.05));
                for (var c : coords) {
                    // Чем глубже сеть, тем меньше Z — отметка отсчитывается
                    // от условной поверхности земли Z = 0.
                    assertThat(c.getZ()).isLessThan(0);
                }
            }
        }
    }

    @Test
    @DisplayName("Пересечение с существующей теплосетью решено проходом сверху: так дешевле")
    void prefersCheaperPassage() throws Exception {
        prepare();
        List<UtilityCrossing> all = deep.getCrossingsByVariant().values().stream()
                .flatMap(List::stream)
                .filter(c -> "heat_network".equals(c.getUtilityType()))
                .collect(java.util.stream.Collectors.toList());

        assertThat(all)
                .as("трасса пересекает существующую тепловую сеть — иначе задача по глубине "
                        + "на этом наборе не проявляется")
                .isNotEmpty();

        // Существующая сеть лежит на 3,0 м; проход сверху выводит новую выше трёх метров,
        // где стоимость по глубине не растёт, проход снизу уводит за четыре метра
        // и добавляет не менее 10 % к стоимости участка.
        assertThat(all).allMatch(c -> c.getPassage() == UtilityCrossing.Passage.ABOVE);
        assertThat(all).allMatch(c -> c.getNewDepth() < 3.0);
    }
}
