package ru.lct.heatroute.compliance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.geo.Geo;
import ru.lct.heatroute.ingest.GeoJsonStreamParser;
import ru.lct.heatroute.ingest.RawFeature;
import ru.lct.heatroute.ingest.SceneAssembler;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правило четырёх примыканий к тепловой камере (раздел 2.1 ТП, разъяснение №12).
 * <p>
 * Считать примыкания по одним новым участкам недостаточно. Существующая линия,
 * проходящая через камеру, делится ею на две части и занимает два примыкания из четырёх,
 * так что новых участков к такой камере подойдёт только два. Камера, поставленная
 * в конце существующей линии, отбирает одно примыкание, и новых участков к ней подходит
 * три. По самой выгрузке эти две камеры неразличимы — разницу видно только во входном
 * наборе, поэтому обстановка здесь собирается настоящим разбором, а выгрузка задаётся
 * атрибутами, как их и видит проверка.
 */
@SpringBootTest
@ActiveProfiles("nodb")
class ChamberDegreeTest {

    private static final int DN = 200;

    @Autowired
    GeoJsonStreamParser parser;
    @Autowired
    SceneAssembler assembler;
    @Autowired
    ComplianceChecker checker;
    @Autowired
    ReferenceCatalog catalog;

    @Test
    @DisplayName("Транзитная существующая линия занимает два примыкания из четырёх")
    void transitLineTakesTwoAttachments() throws Exception {
        Scene scene = existingLine();

        assertThat(degreeFindings(scene, scene.middle, 2))
                .as("два новых участка и две части разделённой линии — ровно предел")
                .isEmpty();
        assertThat(degreeFindings(scene, scene.middle, 3))
                .as("три новых участка и две части разделённой линии дают пять примыканий; "
                        + "по одним новым участкам такая камера выглядит законной")
                .isNotEmpty();
    }

    @Test
    @DisplayName("Камера в конце существующей линии занимает одно примыкание")
    void lineEndTakesOneAttachment() throws Exception {
        Scene scene = existingLine();

        assertThat(degreeFindings(scene, scene.end, 3))
                .as("линия в камере заканчивается, а не проходит через неё: занято одно "
                        + "примыкание, и три новых участка укладываются в предел")
                .isEmpty();
        assertThat(degreeFindings(scene, scene.end, 4))
                .as("четыре новых участка при одном занятом примыкании — уже пять")
                .isNotEmpty();
    }

    @Test
    @DisplayName("Диаметр камеры задаёт самая толстая существующая линия под ней")
    void chamberDiameterTakesTheThickestHostLine() throws Exception {
        // Две существующие линии заканчиваются в одной точке: первая тоньше новой сети,
        // вторая толще. Диаметр камеры задаёт наибольший ДУ среди всех примыкающих.
        Scene scene = scene(dataset(
                network("e1", 100, 37.6400, 55.7000, 37.6410, 55.7000),
                network("e2", 600, 37.6410, 55.7000, 37.6420, 55.7000)));
        Coordinate junction = scene.model.getSegmentsById().get("e1")
                .getGeometry().getCoordinateN(1);

        Set<ComplianceRule> declaredByThickest = rulesOf(checker.check(
                exportWithChamber(scene, junction, 1, 600), scene.model));
        assertThat(declaredByThickest)
                .as("камера объявлена по наибольшему ДУ 600, и это верно")
                .doesNotContain(ComplianceRule.CHAMBER_DIAMETER);

        Set<ComplianceRule> declaredByFirst = rulesOf(checker.check(
                exportWithChamber(scene, junction, 1, 100), scene.model));
        assertThat(declaredByFirst)
                .as("диаметр по первой встреченной линии — занижение, его надо назвать")
                .contains(ComplianceRule.CHAMBER_DIAMETER);
    }

    // =================================================================================

    /** Обстановка с существующей сетью и характерные точки на ней. */
    private static final class Scene {
        InputScene model;
        Coordinate middle;
        Coordinate end;
    }

    private Scene existingLine() throws Exception {
        Scene scene = scene(dataset(network("e1", DN, 37.6400, 55.7000, 37.6440, 55.7000)));
        LineString line = scene.model.getSegmentsById().get("e1").getGeometry();
        Coordinate from = line.getCoordinateN(0);
        Coordinate to = line.getCoordinateN(1);
        scene.middle = new Coordinate((from.x + to.x) / 2, (from.y + to.y) / 2);
        scene.end = to.copy();
        return scene;
    }

    /** Нарушения правила четырёх примыканий для камеры в указанной точке. */
    private List<ComplianceFinding> degreeFindings(Scene scene, Coordinate at,
                                                  int newSegments) {
        ComplianceReport report = checker.check(
                exportWithChamber(scene, at, newSegments, DN), scene.model);
        return report.getFindings().stream()
                .filter(f -> f.getRule() == ComplianceRule.CHAMBER_DEGREE)
                .collect(Collectors.toList());
    }

    /**
     * Выгрузка из камеры, расходящихся от неё участков и сводной записи. Участки
     * расходятся веером в северную полуплоскость: правило считает примыкания, и лишнее
     * наложение линий друг на друга или на существующую сеть добавляло бы в отчёт
     * нарушения не по делу.
     */
    private List<RawFeature> exportWithChamber(Scene scene, Coordinate at,
                                               int newSegments, int chamberDn) {
        List<RawFeature> out = new ArrayList<>();
        for (int i = 0; i < newSegments; i++) {
            double angle = Math.PI / (newSegments + 1) * (i + 1);
            Coordinate far = new Coordinate(at.x + 40 * Math.cos(angle),
                    at.y + 40 * Math.sin(angle));
            LineString line = Geo.FACTORY.createLineString(
                    new Coordinate[]{at.copy(), far});

            Map<String, Object> props = new LinkedHashMap<>();
            props.put("id", "new_" + i);
            props.put("object_type", "heat_network");
            props.put("variant_id", "v1");
            props.put("start_node_id", "ch_1");
            props.put("end_node_id", "t_" + i);
            props.put("flow_tph", 10.0);
            props.put("diameter", (long) DN);
            props.put("length", line.getLength());
            props.put("laying_method", "base");
            props.put("depth_start", 3.0);
            props.put("depth_end", 3.0);
            props.put("cost", line.getLength() * catalog.newCostPerM(DN));
            out.add(new RawFeature(props, line));
        }

        Map<String, Object> chamber = new LinkedHashMap<>();
        chamber.put("id", "ch_1");
        chamber.put("object_type", "heat_chamber");
        chamber.put("variant_id", "v1");
        chamber.put("diameter", (long) chamberDn);
        chamber.put("cost", catalog.chamberCost(chamberDn));
        out.add(new RawFeature(chamber, Geo.point(at)));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", "v1_summary");
        summary.put("object_type", "variant_summary");
        summary.put("variant_id", "v1");
        summary.put("rank", 1L);
        summary.put("construction_cost", 0.0);
        summary.put("chamber_construction_cost", 0.0);
        summary.put("existing_chamber_tie_in_count", 0L);
        summary.put("existing_chamber_tie_in_cost", 0.0);
        summary.put("unconnected_penalty", 0.0);
        summary.put("calculated_cost", 0.0);
        summary.put("new_network_length", 0.0);
        summary.put("score", 0.0);
        summary.put("unconnected_oks_ids", List.of());
        out.add(new RawFeature(summary, null));
        return out;
    }

    private static Set<ComplianceRule> rulesOf(ComplianceReport report) {
        return report.getFindings().stream()
                .map(ComplianceFinding::getRule)
                .collect(Collectors.toSet());
    }

    private static String dataset(String... features) {
        return "{\"type\":\"FeatureCollection\",\"features\":["
                + String.join(",", features) + "]}";
    }

    private static String network(String id, int dn, double lon1, double lat1,
                                  double lon2, double lat2) {
        return "{\"type\":\"Feature\",\"properties\":{\"id\":\"" + id
                + "\",\"object_type\":\"heat_network\",\"diameter\":" + dn
                + "},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[["
                + lon1 + "," + lat1 + "],[" + lon2 + "," + lat2 + "]]}}";
    }

    private Scene scene(String json) throws Exception {
        SceneAssembler.Collector collector = new SceneAssembler.Collector();
        parser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                collector::accept);
        Scene scene = new Scene();
        scene.model = assembler.assemble(collector);
        return scene;
    }
}
