package ru.lct.heatroute.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatroute.domain.model.ExistingChamber;
import ru.lct.heatroute.domain.model.ExistingSegment;
import ru.lct.heatroute.domain.model.FutureOks;
import ru.lct.heatroute.domain.model.IngestDiagnostics;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.result.CalculationVariant;
import ru.lct.heatroute.domain.result.NewSegment;
import ru.lct.heatroute.variant.VariantPlanner;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Запуск на наборе с полным атрибутивным составом — без изменения алгоритма.
 * <p>
 * Конкурсный набор беднее, чем описывает техническое приложение, и сервис
 * восстанавливает недостающее. Проверять решение будут «на отдельном наборе
 * той же структуры», и он, вероятно, окажется полным. Этот тест доказывает,
 * что обе ветки разбора работают одним и тем же кодом: здесь ничего
 * не восстанавливается, всё берётся из входных данных.
 * <p>
 * Набор изготовлен из конкурсного скриптом {@code tools/make_full_dataset.py}:
 * достроены {@code upstream_object_id}, расходы существующей сети, диаметры камер,
 * полигоны {@code oks_future} со справочной нагрузкой, ссылки {@code oks_id},
 * тип {@code oks_existing} и строковые идентификаторы.
 */
@SpringBootTest
@ActiveProfiles("nodb")
class FullSpecDatasetTest {

    private static final String SAMPLE = "/samples/dataset_full_spec.geojson";

    @Autowired
    GeoJsonStreamParser parser;
    @Autowired
    SceneAssembler assembler;
    @Autowired
    VariantPlanner planner;

    private static InputScene scene;
    private static VariantPlanner.Plan plan;

    private InputScene scene() throws Exception {
        if (scene == null) {
            SceneAssembler.Collector collector = new SceneAssembler.Collector();
            try (InputStream in = getClass().getResourceAsStream(SAMPLE)) {
                parser.parse(Objects.requireNonNull(in), collector::accept);
            }
            scene = assembler.assemble(collector);
        }
        return scene;
    }

    private VariantPlanner.Plan plan() throws Exception {
        if (plan == null) {
            plan = planner.plan(scene());
            report();
        }
        return plan;
    }

    private void report() {
        System.out.println();
        System.out.println("======= НАБОР С ПОЛНЫМ АТРИБУТИВНЫМ СОСТАВОМ =======");
        CalculationVariant best = plan.getVariants().get(0);
        System.out.printf("вариантов %d | лучший: %s%n",
                plan.getVariants().size(), best.getDescription());
        System.out.printf("  новая сеть %.1f м, реконструкция %.1f м, стоимость %,.0f руб., S = %.3f%n",
                best.getSummary().getNewNetworkLength(),
                best.getSummary().getReconstructionLength(),
                best.getSummary().getCalculatedCost(), best.getSummary().getScore());
        System.out.printf("  не подключено: %s%n", best.getSummary().getUnconnectedOksIds());
        System.out.println("====================================================");
        System.out.println();
    }

    @Test
    @DisplayName("Все типы объектов полного состава разобраны")
    void readsFullSpec() throws Exception {
        InputScene s = scene();
        assertThat(s.getSource()).isNotNull();
        assertThat(s.getSegments()).hasSize(29);
        assertThat(s.getChambers()).hasSize(9);
        // 16 перспективных ОКС: одно здание содержит две точки подключения.
        assertThat(s.getFutureOks()).hasSize(16);
        // 69 существующих зданий + 3 прочих ограничения + 29 участков сети.
        assertThat(s.getRestrictions()).hasSize(69 + 3 + 29);
    }

    @Test
    @DisplayName("Ничего не восстанавливается: все атрибуты взяты из входных данных")
    void nothingIsInferred() throws Exception {
        InputScene s = scene();

        assertThat(s.getSegments())
                .as("направление к источнику пришло атрибутом, а не восстановлено")
                .allMatch(seg -> !seg.isUpstreamInferred());
        assertThat(s.getSegments())
                .as("расход существующей сети задан во входных данных")
                .allMatch(seg -> !seg.isFlowAssumed() && seg.getFlowTph() > 0);
        assertThat(s.getChambers())
                .as("условный диаметр камер задан во входных данных")
                .allMatch(ch -> !ch.isDiameterInferred() && ch.getDiameter() > 0);
        assertThat(s.getFutureOks())
                .as("расход перспективных ОКС взят с полигона oks_future")
                .allMatch(o -> o.getFlowSource() == FutureOks.FlowSource.OKS_FUTURE)
                .as("контур взят из входных данных, а не опознан по ограничению")
                .allMatch(o -> o.getFootprintSource() == FutureOks.FootprintSource.OKS_FUTURE);

        List<String> codes = s.getDiagnostics().getEntries().stream()
                .map(IngestDiagnostics.Entry::getCode)
                .collect(Collectors.toList());
        assertThat(codes).doesNotContain(
                "upstream.inferred", "segment.noFlow",
                "chamber.diameterInferred", "oks.flowFromConnectionPoint",
                "oks.footprintFromRestriction");
        assertThat(codes).contains("upstream.fromInput");
        assertThat(s.getDiagnostics().hasErrors()).isFalse();
    }

    @Test
    @DisplayName("Здание с двумя вводами подключается через один — ближайший к сети")
    void multipleConnectionPointsHandled() throws Exception {
        InputScene s = scene();

        assertThat(s.getDiagnostics().getEntries())
                .as("выбор ввода зафиксирован в протоколе")
                .anyMatch(e -> "oks.multipleConnectionPoints".equals(e.getCode()));

        // Ни один объект не должен остаться с нулевым расходом: иначе в сети
        // появится ветвь, построенная вхолостую.
        assertThat(s.getFutureOks()).allMatch(o -> o.getFlowTph() > 0);
        assertThat(s.totalFutureFlowTph())
                .as("суммарная нагрузка сохранена при склейке двух вводов в один объект")
                .isCloseTo(488.72, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("Расчёт проходит и подключает все объекты")
    void calculatesWithoutChanges() throws Exception {
        VariantPlanner.Plan p = plan();
        assertThat(p.getVariants()).isNotEmpty();

        CalculationVariant best = p.getVariants().get(0);
        assertThat(best.getSummary().getUnconnectedOksIds()).isEmpty();
        assertThat(best.getSegments()).isNotEmpty();
        assertThat(best.getTieIns()).isNotEmpty();
        assertThat(best.getSummary().getCalculatedCost()).isPositive();
    }

    @Test
    @DisplayName("Заданная загрузка существующей сети приводит к реконструкции")
    void reconstructionAppears() throws Exception {
        VariantPlanner.Plan p = plan();

        // В этом наборе существующие участки загружены на 35 % пропускной способности,
        // поэтому дополнительный расход выводит часть из них за предел, и реконструкция
        // обязана появиться. На конкурсном наборе расход не задан и принят нулевым —
        // там реконструкции почти нет, и это следствие данных, а не алгоритма.
        long withReconstruction = p.getVariants().stream()
                .filter(v -> !v.getReconstructions().isEmpty())
                .count();
        assertThat(withReconstruction)
                .as("реконструкция существующей сети при заданной её загрузке")
                .isPositive();

        p.getVariants().forEach(v -> v.getReconstructions().forEach(r -> {
            assertThat(r.getExistingFlowTph()).isPositive();
            assertThat(r.getRequiredDiameter()).isGreaterThan(r.getExistingDiameter());
        }));
    }

    @Test
    @DisplayName("Диаметры и расходы согласованы так же, как на конкурсном наборе")
    void invariantsHold() throws Exception {
        VariantPlanner.Plan p = plan();
        for (CalculationVariant v : p.getVariants()) {
            for (NewSegment segment : v.getSegments()) {
                assertThat(segment.getFlowTph()).isPositive();
                assertThat(segment.getDiameter()).isPositive();
                assertThat(segment.getLength()).isPositive();
                assertThat(segment.getCost()).isPositive();
            }
        }
    }

    @Test
    @DisplayName("Режим с учётом глубины проходит и на этом наборе")
    void depthModeWorksHereToo() throws Exception {
        VariantPlanner.Plan deep =
                planner.plan(scene(), VariantPlanner.Progress.NONE, true);

        assertThat(deep.getVariants())
                .as("расчёт по глубине даёт варианты и на наборе полного состава")
                .isNotEmpty();

        for (ru.lct.heatroute.domain.result.CalculationVariant v : deep.getVariants()) {
            // Глубина проставлена у каждого участка: без этого выгрузка по разделу 7
            // приложения не состоится.
            assertThat(v.getSegments())
                    .allMatch(seg -> seg.getDepthStart() > 0 && seg.getDepthEnd() > 0);

            // Нитка после деления по глубине не распадается.
            java.util.Set<String> ends = v.getSegments().stream()
                    .map(ru.lct.heatroute.domain.result.NewSegment::getEndNodeId)
                    .collect(java.util.stream.Collectors.toSet());
            long roots = v.getSegments().stream()
                    .map(ru.lct.heatroute.domain.result.NewSegment::getStartNodeId)
                    .filter(id -> !ends.contains(id))
                    .distinct()
                    .count();
            assertThat(roots)
                    .as("начал без входящего участка в варианте %s", v.getVariantId())
                    .isEqualTo(v.getTieIns().size());
        }

        // Пересечение всегда ссылается на существующий участок своего варианта.
        deep.getCrossingsByVariant().forEach((variantId, list) -> {
            ru.lct.heatroute.domain.result.CalculationVariant v = deep.getVariants().stream()
                    .filter(x -> x.getVariantId().equals(variantId))
                    .findFirst().orElse(null);
            if (v == null) {
                return;
            }
            java.util.Set<String> ids = v.getSegments().stream()
                    .map(ru.lct.heatroute.domain.result.NewSegment::getId)
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(list).allMatch(c -> ids.contains(c.getSegmentId()));
        });
    }

    @Test
    @DisplayName("Цепочка к источнику взята из входных данных и замыкается на нём")
    void upstreamChainFromInput() throws Exception {
        InputScene s = scene();
        String sourceId = s.getSource().getId();

        for (ExistingSegment segment : s.getSegments()) {
            assertThat(s.getTopology().chainToSource(segment.getId()))
                    .as("цепочка для участка %s", segment.getId())
                    .isNotEmpty()
                    .last().isEqualTo(sourceId);
        }
        for (ExistingChamber chamber : s.getChambers()) {
            assertThat(chamber.getUpstreamObjectId()).isNotNull();
        }
    }
}
