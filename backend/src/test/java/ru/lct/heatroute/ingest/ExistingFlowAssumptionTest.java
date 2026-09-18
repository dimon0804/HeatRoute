package ru.lct.heatroute.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatroute.domain.model.ExistingSegment;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.result.CalculationVariant;
import ru.lct.heatroute.variant.VariantPlanner;

import java.io.InputStream;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Допущение о загрузке существующей сети, задаваемое на один расчёт.
 * <p>
 * Расхода существующих участков в конкурсном наборе нет, и принятое значение — самое
 * влиятельное допущение решения: от него зависит, какие участки попадут под
 * реконструкцию. Проверяется, что допущение действительно меняет результат и что
 * оно не протекает между расчётами: расчёты идут в несколько потоков, и подмена
 * общих настроек означала бы, что один расчёт портит другой.
 */
@SpringBootTest
@ActiveProfiles("nodb")
class ExistingFlowAssumptionTest {

    @Autowired
    GeoJsonStreamParser parser;
    @Autowired
    SceneAssembler assembler;
    @Autowired
    VariantPlanner planner;
    @Autowired
    IngestProperties props;

    private SceneAssembler.Collector read() throws Exception {
        SceneAssembler.Collector collector = new SceneAssembler.Collector();
        try (InputStream in = getClass().getResourceAsStream("/samples/dataset_lct2026.geojson")) {
            parser.parse(Objects.requireNonNull(in), collector::accept);
        }
        return collector;
    }

    @Test
    @DisplayName("При нулевом допущении расход существующих участков нулевой")
    void zeroModeLeavesNoFlow() throws Exception {
        InputScene scene = assembler.assemble(read(),
                IngestProperties.ExistingFlowMode.ZERO, null);

        assertThat(scene.getSegments())
                .allSatisfy(s -> assertThat(s.getFlowTph()).isZero());
    }

    @Test
    @DisplayName("Доля пропускной способности даёт расход по диаметру участка")
    void capacityFractionFillsFlow() throws Exception {
        InputScene scene = assembler.assemble(read(),
                IngestProperties.ExistingFlowMode.CAPACITY_FRACTION, 0.5);

        assertThat(scene.getSegments())
                .allSatisfy(s -> assertThat(s.getFlowTph()).isPositive());

        // Расход пропорционален пропускной способности, а она растёт с диаметром:
        // участок большего ДУ не может нести меньше участка меньшего ДУ.
        ExistingSegment thin = scene.getSegments().stream()
                .min(java.util.Comparator.comparingInt(ExistingSegment::getDiameter)).orElseThrow();
        ExistingSegment thick = scene.getSegments().stream()
                .max(java.util.Comparator.comparingInt(ExistingSegment::getDiameter)).orElseThrow();
        assertThat(thick.getFlowTph()).isGreaterThan(thin.getFlowTph());
    }

    @Test
    @DisplayName("Допущение не протекает: настройки сервиса остаются прежними")
    void assumptionDoesNotLeak() throws Exception {
        IngestProperties.ExistingFlowMode before = props.getExistingFlowMode();
        double fractionBefore = props.getExistingFlowCapacityFraction();

        assembler.assemble(read(), IngestProperties.ExistingFlowMode.CAPACITY_FRACTION, 0.9);

        assertThat(props.getExistingFlowMode()).isEqualTo(before);
        assertThat(props.getExistingFlowCapacityFraction()).isEqualTo(fractionBefore);

        // И следующий разбор без указания режима идёт по настройкам сервиса.
        InputScene scene = assembler.assemble(read());
        assertThat(scene.getSegments())
                .allSatisfy(s -> assertThat(s.getFlowTph()).isZero());
    }

    @Test
    @DisplayName("Загруженная сеть увеличивает объём реконструкции")
    void loadedNetworkNeedsMoreReconstruction() throws Exception {
        InputScene empty = assembler.assemble(read(),
                IngestProperties.ExistingFlowMode.ZERO, null);
        InputScene loaded = assembler.assemble(read(),
                IngestProperties.ExistingFlowMode.CAPACITY_FRACTION, 0.5);

        CalculationVariant bestEmpty = planner.plan(empty).getVariants().get(0);
        CalculationVariant bestLoaded = planner.plan(loaded).getVariants().get(0);

        System.out.printf("%nДопущение о загрузке существующей сети:%n"
                        + "  расход 0      → реконструкция %.1f м, %,.0f руб., S = %.3f%n"
                        + "  50%% ёмкости   → реконструкция %.1f м, %,.0f руб., S = %.3f%n%n",
                bestEmpty.getSummary().getReconstructionLength(),
                bestEmpty.getSummary().getReconstructionCost(),
                bestEmpty.getSummary().getScore(),
                bestLoaded.getSummary().getReconstructionLength(),
                bestLoaded.getSummary().getReconstructionCost(),
                bestLoaded.getSummary().getScore());

        assertThat(bestLoaded.getSummary().getReconstructionLength())
                .as("сеть, уже несущая расход, требует не меньше реконструкции")
                .isGreaterThanOrEqualTo(bestEmpty.getSummary().getReconstructionLength());
    }
}
