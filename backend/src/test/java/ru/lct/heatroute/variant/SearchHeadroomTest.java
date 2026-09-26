package ru.lct.heatroute.variant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.result.CalculationVariant;
import ru.lct.heatroute.ingest.GeoJsonStreamParser;
import ru.lct.heatroute.ingest.SceneAssembler;
import ru.lct.heatroute.routing.RoutingProperties;

import java.io.InputStream;
import java.util.Objects;

/**
 * Замер запаса: улучшается ли решение, если дать поиску больше времени.
 * <p>
 * Не проверка, а измерение — поэтому запускается только по требованию:
 * {@code mvn test -Dtest=SearchHeadroomTest -Dheadroom=on}. Прогон занимает минуты.
 * <p>
 * Вопрос, на который отвечает замер: упирается ли качество решения в число попыток
 * или в набор ходов эвристики. Если увеличение попыток вдесятеро не двигает `S`,
 * значит, поиск стоит в локальном оптимуме и дальше помогут новые ходы, а не время.
 */
@SpringBootTest
@ActiveProfiles("nodb")
@EnabledIfSystemProperty(named = "headroom", matches = "on")
class SearchHeadroomTest {

    @Autowired
    GeoJsonStreamParser parser;
    @Autowired
    SceneAssembler assembler;
    @Autowired
    VariantPlanner planner;
    @Autowired
    RoutingProperties props;

    @Test
    @DisplayName("Сколько даёт увеличение числа попыток")
    void measure() throws Exception {
        SceneAssembler.Collector collector = new SceneAssembler.Collector();
        try (InputStream in = getClass().getResourceAsStream("/samples/dataset_lct2026.geojson")) {
            parser.parse(Objects.requireNonNull(in), collector::accept);
        }
        InputScene scene = assembler.assemble(collector);

        int[][] grid = {
                // затравки, разбиения, кандидатов врезки
                {8, 3, 12},
                {16, 3, 12},
                {8, 3, 24},
                {8, 5, 12},
                {16, 5, 24},
                {32, 6, 36},
        };

        System.out.println();
        System.out.println("============== ЗАПАС ПОИСКА ==============");
        System.out.printf("%8s %10s %10s %12s %10s%n",
                "затравок", "разбиений", "врезок", "лучший S", "время, с");

        for (int[] row : grid) {
            props.setSteinerRestarts(row[0]);
            props.setMaxOksPartitions(row[1]);
            props.setTieInShortlistSize(row[2]);

            long started = System.nanoTime();
            VariantPlanner.Plan plan = planner.plan(scene);
            double seconds = (System.nanoTime() - started) / 1e9;

            double best = plan.getVariants().stream()
                    .mapToDouble(v -> v.getSummary().getScore())
                    .min().orElse(Double.NaN);
            System.out.printf("%8d %10d %10d %12.3f %10.1f%n",
                    row[0], row[1], row[2], best, seconds);
        }
        System.out.println("==========================================");
        System.out.println();

        // Возврат к значениям по умолчанию: контекст Spring общий на все тесты.
        props.setSteinerRestarts(8);
        props.setMaxOksPartitions(3);
        props.setTieInShortlistSize(12);
    }

    /** Из чего складывается лучший вариант — чтобы видеть, где именно тратятся деньги. */
    @Test
    @DisplayName("Структура затрат лучшего варианта")
    void costBreakdown() throws Exception {
        SceneAssembler.Collector collector = new SceneAssembler.Collector();
        try (InputStream in = getClass().getResourceAsStream("/samples/dataset_lct2026.geojson")) {
            parser.parse(Objects.requireNonNull(in), collector::accept);
        }
        VariantPlanner.Plan plan = planner.plan(assembler.assemble(collector));
        CalculationVariant best = plan.getVariants().get(0);

        // Стоимость строительства — это участки плюс камеры плюс врезки, поэтому
        // на трубу приходится остаток после двух известных слагаемых.
        double pipe = best.getSummary().getConstructionCost()
                - best.getSummary().getChamberConstructionCost()
                - best.getSummary().getExistingChamberTieInCost();

        System.out.println();
        System.out.println("=========== СТРУКТУРА ЗАТРАТ ЛУЧШЕГО ВАРИАНТА ===========");
        System.out.printf("Труба      %,15.0f руб. за %.1f м%n",
                pipe, best.getSummary().getNewNetworkLength());
        System.out.printf("Камеры     %,15.0f руб. за %d шт.%n",
                best.getSummary().getChamberConstructionCost(), best.getChambers().size());
        System.out.printf("Врезки     %,15.0f руб. за %d шт.%n",
                best.getSummary().getExistingChamberTieInCost(),
                best.getSummary().getExistingChamberTieInCount());
        System.out.printf("Штраф      %,15.0f руб.%n", best.getSummary().getUnconnectedPenalty());
        System.out.println("Камеры по диаметрам:");
        best.getChambers().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        c -> c.getDiameter(), java.util.TreeMap::new,
                        java.util.stream.Collectors.counting()))
                .forEach((dn, count) -> System.out.printf("    ДУ %4d — %d шт. по %,.0f руб.%n",
                        dn, count, best.getChambers().stream()
                                .filter(c -> c.getDiameter() == dn)
                                .findFirst().map(c -> c.getCost()).orElse(0.0)));
        System.out.println("========================================================");
        System.out.println();
    }
}
