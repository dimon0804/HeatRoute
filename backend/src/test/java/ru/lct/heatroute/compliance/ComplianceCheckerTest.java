package ru.lct.heatroute.compliance;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.export.GeoJsonResultWriter;
import ru.lct.heatroute.ingest.GeoJsonStreamParser;
import ru.lct.heatroute.ingest.RawFeature;
import ru.lct.heatroute.ingest.SceneAssembler;
import ru.lct.heatroute.variant.VariantPlanner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверка соответствия выгрузки приложению, проверенная на самой себе.
 * <p>
 * Проверка идёт по настоящему пути файла: расчёт, запись выгрузки, разбор её обратно
 * и только потом сверка. Именно на этом пути и живут дефекты контракта, которых
 * не видно из объектов в памяти: значение могло не дойти до файла или приехать другим
 * типом. Проверять выгрузку по внутренним структурам означало бы не проверять её вовсе.
 * <p>
 * Половина тестов здесь — о том, что проверка умеет ругаться. Валидатор, который всегда
 * говорит «нарушений нет», ничего не стоит, поэтому в выгрузку по одному вносятся
 * поломки, и каждая обязана быть названа своим правилом.
 */
@SpringBootTest
@ActiveProfiles("nodb")
class ComplianceCheckerTest {

    @Autowired
    GeoJsonStreamParser parser;
    @Autowired
    SceneAssembler assembler;
    @Autowired
    VariantPlanner planner;
    @Autowired
    GeoJsonResultWriter writer;
    @Autowired
    ComplianceChecker checker;

    private static InputScene scene;
    private static List<RawFeature> exported;

    @BeforeAll
    static void reset() {
        scene = null;
        exported = null;
    }

    /** Расчёт и выгрузка делаются один раз: на конкурсном наборе это десятки секунд. */
    private void prepare() throws Exception {
        if (exported != null) {
            return;
        }
        SceneAssembler.Collector collector = new SceneAssembler.Collector();
        try (InputStream in = getClass().getResourceAsStream("/samples/dataset_lct2026.geojson")) {
            parser.parse(in, collector::accept);
        }
        scene = assembler.assemble(collector);

        VariantPlanner.Plan plan = planner.plan(scene, (f, s) -> { },
                VariantPlanner.Options.builder().build());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(out, plan.getVariants());

        List<RawFeature> features = new ArrayList<>();
        parser.parse(new ByteArrayInputStream(out.toByteArray()), features::add);
        exported = features;
    }

    @Test
    @DisplayName("Собственная выгрузка проходит проверку без нарушений")
    void ownExportIsCompliant() throws Exception {
        prepare();
        ComplianceReport report = checker.check(exported, scene);

        assertThat(report.getChecks())
                .as("проверок должно быть много: «нарушений нет» при нуле сверок ничего "
                        + "не значит")
                .isGreaterThan(500);
        assertThat(report.getFindings())
                .as("нарушения в собственной выгрузке")
                .isEmpty();
        assertThat(report.getObjectCounts().keySet())
                .as("в выгрузке только четыре типа объектов раздела 7.1")
                .containsOnly("heat_network", "heat_chamber", "technical_node",
                        "variant_summary");
    }

    @Test
    @DisplayName("Лишний тип объекта в выгрузке называется своим правилом")
    void catchesForeignObjectType() throws Exception {
        prepare();
        List<RawFeature> broken = new ArrayList<>(exported);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", "tie_1");
        properties.put("object_type", "tie_in");
        properties.put("variant_id", firstVariantId());
        broken.add(new RawFeature(properties, null));

        assertThat(rulesOf(checker.check(broken, scene)))
                .contains(ComplianceRule.OBJECT_TYPE_ALLOWED);
    }

    @Test
    @DisplayName("Пропавший атрибут глубины называется своим правилом")
    void catchesMissingDepthAttribute() throws Exception {
        prepare();
        List<RawFeature> broken = copyWithChange("heat_network", 0,
                properties -> properties.remove("depth_start"));

        assertThat(rulesOf(checker.check(broken, scene)))
                .as("именно этот дефект уехал бы в сдачу: пустое значение глубины "
                        + "пропадало при записи в базу")
                .contains(ComplianceRule.REQUIRED_ATTRIBUTES);
    }

    @Test
    @DisplayName("Заниженный диаметр виден и по расходу, и по монотонности")
    void catchesUnderSizedDiameter() throws Exception {
        prepare();
        List<RawFeature> broken = copyWithChange("heat_network", 1,
                properties -> properties.put("diameter", 50L));

        Set<ComplianceRule> rules = rulesOf(checker.check(broken, scene));
        assertThat(rules).contains(ComplianceRule.DIAMETER_CAPACITY);
        assertThat(rules)
                .as("диаметр, упавший посреди пути, нарушает и монотонность")
                .contains(ComplianceRule.DIAMETER_MONOTONIC);
    }

    @Test
    @DisplayName("Соврать в длине участка не получится: сверяется с геометрией")
    void catchesWrongLength() throws Exception {
        prepare();
        List<RawFeature> broken = copyWithChange("heat_network", 2,
                properties -> properties.put("length", 5.0));

        assertThat(rulesOf(checker.check(broken, scene)))
                .contains(ComplianceRule.LENGTH_MATCHES_GEOMETRY);
    }

    @Test
    @DisplayName("Стоимость участка и камеры пересчитываются заново")
    void catchesWrongCost() throws Exception {
        prepare();
        List<RawFeature> brokenSegment = copyWithChange("heat_network", 0,
                properties -> properties.put("cost", 1.0));
        assertThat(rulesOf(checker.check(brokenSegment, scene)))
                .contains(ComplianceRule.SEGMENT_COST);

        List<RawFeature> brokenChamber = copyWithChange("heat_chamber", 0,
                properties -> properties.put("cost", 111.0));
        assertThat(rulesOf(checker.check(brokenChamber, scene)))
                .contains(ComplianceRule.CHAMBER_COST);
    }

    @Test
    @DisplayName("Подтасованный показатель ранжирования пересчитывается по формуле")
    void catchesWrongScore() throws Exception {
        prepare();
        List<RawFeature> broken = copyWithChange("variant_summary", 0,
                properties -> properties.put("score", 0.001));

        assertThat(rulesOf(checker.check(broken, scene)))
                .as("занижение показателя — самый выгодный способ обмануть ранжирование, "
                        + "и он должен ловиться в первую очередь")
                .contains(ComplianceRule.SCORE_FORMULA);
    }

    @Test
    @DisplayName("Стоимость строительства сверяется с суммой объектов")
    void catchesSummaryMismatch() throws Exception {
        prepare();
        List<RawFeature> broken = copyWithChange("variant_summary", 0,
                properties -> properties.put("construction_cost", 1.0));

        assertThat(rulesOf(checker.check(broken, scene)))
                .contains(ComplianceRule.SUMMARY_SUMS);
    }

    @Test
    @DisplayName("Без входного набора проверяются только правила, для которых он не нужен")
    void worksWithoutScene() throws Exception {
        prepare();
        ComplianceReport report = checker.check(exported, null);

        assertThat(report.getChecks())
                .as("часть правил проверить всё же можно: состав, геометрия, стоимость")
                .isGreaterThan(100);
        assertThat(report.getFindings())
                .as("на правильной выгрузке нарушений нет и без входного набора")
                .isEmpty();
        assertThat(report.getSkipped())
                .as("непроверенные правила должны быть названы: иначе «нарушений нет» "
                        + "выглядит весомее, чем есть")
                .isNotEmpty();
    }

    // =================================================================================

    private String firstVariantId() {
        return exported.stream()
                .map(f -> f.str("variant_id"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow();
    }

    private static Set<ComplianceRule> rulesOf(ComplianceReport report) {
        return report.getFindings().stream()
                .map(ComplianceFinding::getRule)
                .collect(Collectors.toSet());
    }

    /**
     * Копия выгрузки, в которой изменён один объект заданного типа. Меняется копия
     * атрибутов, а не общий на все тесты объект: иначе поломка одного теста поехала бы
     * в остальные.
     */
    private List<RawFeature> copyWithChange(String objectType, int index,
                                            java.util.function.Consumer<Map<String, Object>> change) {
        List<RawFeature> copy = new ArrayList<>(exported.size());
        int seen = 0;
        for (RawFeature feature : exported) {
            if (objectType.equals(feature.str("object_type")) && seen++ == index) {
                Map<String, Object> properties = new LinkedHashMap<>(feature.properties());
                change.accept(properties);
                copy.add(new RawFeature(properties, feature.geometry()));
            } else {
                copy.add(feature);
            }
        }
        return copy;
    }
}
