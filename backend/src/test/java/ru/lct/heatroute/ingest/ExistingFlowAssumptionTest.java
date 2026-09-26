package ru.lct.heatroute.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatroute.domain.model.ExistingSegment;
import ru.lct.heatroute.domain.model.InputScene;

import java.io.InputStream;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Допущение о загрузке существующей сети, задаваемое на один разбор.
 * <p>
 * В обязательной расчётной модели редакции приложения от 18.09 текущий расход и резерв
 * пропускной способности существующей сети не определяются: реконструкции больше нет,
 * и влиять на результат этому допущению стало нечем. Режим остался исследовательским —
 * им отвечают на вопрос «а что было бы, если сеть уже загружена», и ответ идёт мимо
 * обязательного расчёта.
 * <p>
 * Поэтому здесь проверяется только то, что от режима ещё зависит: значение расхода
 * у разобранных участков и отсутствие протечки допущения в настройки сервиса. Расчёты
 * идут в несколько потоков, и подмена общих настроек означала бы, что один расчёт
 * портит другой.
 */
@SpringBootTest
@ActiveProfiles("nodb")
class ExistingFlowAssumptionTest {

    @Autowired
    GeoJsonStreamParser parser;
    @Autowired
    SceneAssembler assembler;
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

        // И следующий разбор без указания режима идёт по настройкам сервиса,
        // а по ним расход существующей сети не определяется.
        InputScene scene = assembler.assemble(read());
        assertThat(scene.getSegments())
                .allSatisfy(s -> assertThat(s.getFlowTph()).isZero());
    }
}
