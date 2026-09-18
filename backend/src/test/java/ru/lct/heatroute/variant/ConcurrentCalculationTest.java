package ru.lct.heatroute.variant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.geo.Geo;
import ru.lct.heatroute.ingest.GeoJsonStreamParser;
import ru.lct.heatroute.ingest.IngestProperties;
import ru.lct.heatroute.ingest.SceneAssembler;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Параметры расчёта не протекают между одновременными запусками.
 * <p>
 * Пул расчётов — до четырёх потоков, а запретные зоны, расчётный диаметр клиренсов
 * и допущение о загрузке существующей сети задаются на запуск. Если бы хоть один
 * из них хранился в общем состоянии, два одновременных расчёта портили бы друг друга,
 * и поймать это на демонстрации было бы поздно.
 * <p>
 * Проверка прямая: те же расчёты выполняются сначала по одному, потом все сразу,
 * и результаты обязаны совпасть до сотой показателя.
 */
@SpringBootTest
@ActiveProfiles("nodb")
class ConcurrentCalculationTest {

    @Autowired
    GeoJsonStreamParser parser;
    @Autowired
    SceneAssembler assembler;
    @Autowired
    VariantPlanner planner;

    private static InputScene plain;
    private static InputScene loaded;

    @BeforeEach
    void prepare() throws Exception {
        if (plain != null) {
            return;
        }
        plain = assembler.assemble(read(), IngestProperties.ExistingFlowMode.ZERO, null);
        loaded = assembler.assemble(read(),
                IngestProperties.ExistingFlowMode.CAPACITY_FRACTION, 0.5);
    }

    private SceneAssembler.Collector read() throws Exception {
        SceneAssembler.Collector collector = new SceneAssembler.Collector();
        try (InputStream in = getClass().getResourceAsStream("/samples/dataset_lct2026.geojson")) {
            parser.parse(Objects.requireNonNull(in), collector::accept);
        }
        return collector;
    }

    /**
     * Три разных расчёта: обычный, с запретной зоной и на загруженной сети.
     * <p>
     * Зона ставится вокруг точки подключения первого ОКС: так она заведомо влияет
     * на решение, а не оказывается в пустом месте. Расчёт с другим диаметром клиренсов
     * сюда не включён намеренно — он строит собственный граф видимости, и проверка
     * протечек стала бы вдвое дольше, ничего к ней не добавив.
     */
    private List<Callable<Double>> tasks() {
        Coordinate near = plain.getFutureOks().get(0).getConnectionPoint().getCoordinate();
        Geometry zone = Geo.point(near).buffer(30);
        return List.of(
                () -> score(plain, VariantPlanner.Options.flat()),
                () -> score(plain, VariantPlanner.Options.builder()
                        .forbiddenZones(List.of(zone)).build()),
                () -> score(loaded, VariantPlanner.Options.flat()));
    }

    private double score(InputScene scene, VariantPlanner.Options options) {
        VariantPlanner.Plan plan = planner.plan(scene, VariantPlanner.Progress.NONE, options);
        return plan.getVariants().isEmpty() ? -1 : plan.getVariants().get(0).getSummary().getScore();
    }

    @Test
    @DisplayName("Одновременные расчёты с разными параметрами дают те же результаты, что и по одному")
    void parametersDoNotLeakBetweenRuns() throws Exception {
        List<Callable<Double>> tasks = tasks();

        double[] sequential = new double[tasks.size()];
        for (int i = 0; i < tasks.size(); i++) {
            sequential[i] = tasks.get(i).call();
        }

        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Double>> futures = pool.invokeAll(tasks);
            for (int i = 0; i < futures.size(); i++) {
                assertThat(futures.get(i).get(10, TimeUnit.MINUTES))
                        .as("расчёт %d в потоке дал не то же, что по одному", i + 1)
                        .isEqualTo(sequential[i], org.assertj.core.data.Offset.offset(0.01));
            }
        } finally {
            pool.shutdownNow();
        }

        System.out.printf("%nОдновременные расчёты: %s%n%n",
                java.util.Arrays.toString(sequential));
    }
}
