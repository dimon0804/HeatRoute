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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Плотная обстановка — главный риск по производительности.
 * <p>
 * Объём входного файла проверяется отдельно, но опасен не объём. Граф видимости растёт
 * квадратично от числа вершин препятствий, а число обходов — линейно от числа
 * подключаемых объектов. Конкурсный набор даёт семнадцать ОКС; проверочный может дать
 * пятьдесят, и тогда важно, во что превращаются двадцать секунд расчёта.
 * <p>
 * Наборы готовит {@code tools/make_dense_dataset.py}: в те же здания того же квартала
 * добавляются точки подключения. Обстановка остаётся осмысленной, растёт только
 * число объектов, которые нужно подключить.
 * <p>
 * Замер запускается по требованию — {@code mvn test -Dtest=DenseSceneTest -Ddense=on}:
 * он идёт минуты и зависит от машины, поэтому в обычном прогоне ему не место.
 */
@SpringBootTest
@ActiveProfiles("nodb")
@EnabledIfSystemProperty(named = "dense", matches = "on")
class DenseSceneTest {

    private static final Path SAMPLES = Path.of("..", "data", "samples");

    @Autowired
    GeoJsonStreamParser parser;
    @Autowired
    SceneAssembler assembler;
    @Autowired
    VariantPlanner planner;

    @Test
    @DisplayName("Расчёт на уплотнённой обстановке доходит до конца и подключает объекты")
    void densityIsSurvivable() throws Exception {
        System.out.println();
        System.out.println("=============== ПЛОТНОСТЬ ОБСТАНОВКИ ===============");
        System.out.printf("%6s %8s %9s %10s %9s %12s %10s%n",
                "ОКС", "узлов", "рёбер", "время, с", "вариантов", "S лучшего", "не подкл.");

        measure("dataset_lct2026.geojson", 0);
        measure("dense_34.geojson", 0);
        measure("dense_51.geojson", 0);
        measure("dense_85.geojson", 0);

        // Та же обстановка, но клиренс считается по тонкой трубе: проверяем гипотезу,
        // что подключения теряются из-за запаса по диаметру, а не из-за геометрии.
        System.out.println("--- тот же набор, клиренс по ДУ 100 ---");
        measure("dense_34.geojson", 100);
        measure("dense_51.geojson", 100);
        measure("dense_85.geojson", 100);

        System.out.println("====================================================");
        System.out.println();
    }

    private void measure(String file, int designDiameter) throws Exception {
        Path path = SAMPLES.resolve(file);
        if (!Files.exists(path)) {
            System.out.printf("%6s — набор не найден, пропуск (%s)%n", file, path.toAbsolutePath());
            return;
        }

        SceneAssembler.Collector collector = new SceneAssembler.Collector();
        try (InputStream in = Files.newInputStream(path)) {
            parser.parse(in, collector::accept);
        }
        InputScene scene = assembler.assemble(collector);

        long started = System.nanoTime();
        VariantPlanner.Plan plan = planner.plan(scene, VariantPlanner.Progress.NONE,
                VariantPlanner.Options.builder().designDiameter(designDiameter).build());
        double seconds = (System.nanoTime() - started) / 1e9;

        CalculationVariant best = plan.getVariants().isEmpty() ? null : plan.getVariants().get(0);
        System.out.printf("%6d %8d %9d %10.1f %9d %12s %10d%n",
                scene.getFutureOks().size(),
                plan.getGraphNodes(),
                plan.getGraphEdges(),
                seconds,
                plan.getVariants().size(),
                best == null ? "—" : String.format("%.3f", best.getSummary().getScore()),
                best == null ? -1 : best.getSummary().getUnconnectedOksIds().size());

        assertThat(plan.getVariants())
                .as("на наборе %s расчёт обязан дать хотя бы один вариант", file)
                .isNotEmpty();
    }
}
