package ru.lct.heatroute.variant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.result.CalculationVariant;
import ru.lct.heatroute.ingest.GeoJsonStreamParser;
import ru.lct.heatroute.ingest.SceneAssembler;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Запасной источник различий: затравочные терминалы.
 * <p>
 * На конкурсном наборе он не нужен — три разбиения ОКС дают три содержательно разных
 * решения сами. Но проверочный набор может оказаться таким, что все ОКС лежат одной
 * гроздью: разбиение тогда единственное, и «до трёх вариантов» раздела 2.11 ТЗ
 * выполнить больше нечем. Ровно этот случай здесь и воспроизводится — разбиения
 * ограничены одним параметром, как на таком наборе.
 */
@SpringBootTest(properties = "heatroute.routing.max-oks-partitions=1")
@ActiveProfiles("nodb")
class SeededVariantsTest {

    @Autowired
    GeoJsonStreamParser parser;
    @Autowired
    SceneAssembler assembler;
    @Autowired
    VariantPlanner planner;

    /** Расчёт один на класс: он идёт около 13 секунд, а проверок по нему четыре. */
    private static VariantPlanner.Plan plan;

    @BeforeEach
    void prepare() throws Exception {
        if (plan != null) {
            return;
        }
        SceneAssembler.Collector collector = new SceneAssembler.Collector();
        try (InputStream in = getClass().getResourceAsStream("/samples/dataset_lct2026.geojson")) {
            parser.parse(Objects.requireNonNull(in), collector::accept);
        }
        InputScene scene = assembler.assemble(collector);
        plan = planner.plan(scene);

        System.out.println();
        System.out.println("=========== ЕДИНСТВЕННОЕ РАЗБИЕНИЕ ОКС ===========");
        for (CalculationVariant v : plan.getVariants()) {
            System.out.printf("Вариант %s (место %d): участков %d, S = %.3f, "
                            + "не подключено %s%n",
                    v.getVariantId(), v.getSummary().getRank(), v.getSegments().size(),
                    v.getSummary().getScore(), v.getSummary().getUnconnectedOksIds());
        }
        System.out.println("=================================================");
        System.out.println();
    }

    @Test
    @DisplayName("При единственном разбиении варианты всё равно находятся")
    void seedsProduceMoreThanOneVariant() {
        assertThat(plan.getVariants())
                .as("одно разбиение ОКС, но эвристика с разными затравками даёт разные сети")
                .hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("Затравочные варианты различаются формой сети, а не только номером")
    void seededVariantsDifferInShape() {
        List<String> fingerprints = plan.getVariants().stream()
                .map(CalculationVariant::getStructureFingerprint)
                .collect(Collectors.toList());
        assertThat(fingerprints).doesNotHaveDuplicates();

        // Разбиение одно, поэтому и состав частей сети у всех вариантов один.
        // Различие даёт хвост отпечатка — форма дерева.
        List<String> shapes = fingerprints.stream()
                .map(f -> f.substring(f.lastIndexOf('|') + 1))
                .collect(Collectors.toList());
        assertThat(shapes)
                .as("форма сети у вариантов разная")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Затравка не стоит подключения ни одного ОКС")
    void seedsDoNotCostConnections() {
        int best = plan.getVariants().stream()
                .mapToInt(v -> v.getSummary().getUnconnectedOksIds().size())
                .min().orElse(0);
        assertThat(plan.getVariants())
                .allSatisfy(v -> assertThat(v.getSummary().getUnconnectedOksIds())
                        .as("вариант %s подключает не меньше ОКС, чем лучший", v.getVariantId())
                        .hasSizeLessThanOrEqualTo(best));
    }

    @Test
    @DisplayName("Ранжирование остаётся по возрастанию S")
    void rankingStaysOrdered() {
        double previous = -1;
        for (CalculationVariant v : plan.getVariants()) {
            assertThat(v.getSummary().getScore())
                    .as("вариант %s не лучше предыдущего по S", v.getVariantId())
                    .isGreaterThanOrEqualTo(previous);
            previous = v.getSummary().getScore();
        }
    }
}
