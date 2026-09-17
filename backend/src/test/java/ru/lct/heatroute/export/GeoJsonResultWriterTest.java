package ru.lct.heatroute.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.ingest.GeoJsonStreamParser;
import ru.lct.heatroute.ingest.SceneAssembler;
import ru.lct.heatroute.variant.VariantPlanner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт выходного файла (раздел 10 ТП).
 * <p>
 * Проверяется то, что эксперт проверит первым делом, открыв выгрузку: у каждого типа
 * ровно свой обязательный набор атрибутов без чужих полей со значением {@code null},
 * на каждый вариант ровно одна сводная запись с {@code geometry = null}, координаты
 * вернулись в WGS 84, ссылки между объектами разрешаются. Отдельно проверяется, что
 * собственный парсер читает собственную выгрузку — контракт замкнут.
 */
@SpringBootTest
@ActiveProfiles("nodb")
class GeoJsonResultWriterTest {

    @Autowired
    GeoJsonStreamParser parser;
    @Autowired
    SceneAssembler assembler;
    @Autowired
    VariantPlanner planner;
    @Autowired
    GeoJsonResultWriter writer;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Обязательный состав атрибутов по таблицам разделов 10.1–10.7 ТП. */
    private static final Map<String, Set<String>> REQUIRED = new LinkedHashMap<>();

    static {
        REQUIRED.put("heat_network", Set.of("id", "object_type", "variant_id",
                "start_node_id", "end_node_id", "flow_tph", "diameter", "length",
                "laying_method", "depth_start", "depth_end", "cost"));
        REQUIRED.put("tie_in", Set.of("id", "object_type", "variant_id",
                "existing_object_id", "existing_object_type", "existing_diameter",
                "required_diameter", "cost"));
        REQUIRED.put("heat_network_reconstruction", Set.of("id", "object_type", "variant_id",
                "existing_object_id", "existing_flow_tph", "added_flow_tph",
                "calculated_flow_tph", "existing_diameter", "required_diameter",
                "length", "cost"));
        REQUIRED.put("heat_chamber", Set.of("id", "object_type", "variant_id",
                "diameter", "cost"));
        REQUIRED.put("heat_chamber_reconstruction", Set.of("id", "object_type", "variant_id",
                "existing_object_id", "existing_diameter", "required_diameter", "cost"));
        REQUIRED.put("technical_node", Set.of("id", "object_type", "variant_id"));
        // Сверх раздела 10 ТП: требование раздела 7 приложения по глубине.
        REQUIRED.put("depth_crossing", Set.of("id", "object_type", "variant_id", "segment_id",
                "utility_id", "utility_type", "passage", "new_depth", "utility_depth",
                "required_clearance", "actual_clearance"));
        REQUIRED.put("variant_summary", Set.of("id", "object_type", "variant_id", "rank",
                "construction_cost", "chamber_construction_cost", "tie_in_cost",
                "reconstruction_cost", "chamber_reconstruction_cost", "unconnected_penalty",
                "calculated_cost", "new_network_length", "reconstruction_length", "length",
                "score", "unconnected_oks_ids"));
    }

    private static byte[] output;
    private static VariantPlanner.Plan plan;
    private static InputScene scene;

    private JsonNode exported() throws Exception {
        if (output == null) {
            SceneAssembler.Collector collector = new SceneAssembler.Collector();
            try (InputStream in = getClass().getResourceAsStream("/samples/dataset_lct2026.geojson")) {
                parser.parse(Objects.requireNonNull(in), collector::accept);
            }
            scene = assembler.assemble(collector);
            plan = planner.plan(scene);

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            long count = writer.write(buffer, plan.getVariants());
            output = buffer.toByteArray();

            // Выгрузка кладётся рядом с проектом: её открывают в QGIS при разборе
            // результата и прикладывают к сдаче.
            Path target = Path.of("..", "data", "output", "result_lct2026.geojson");
            Files.createDirectories(target.getParent());
            Files.write(target, output);
            System.out.printf("Выгрузка: %d объектов, %,d байт → %s%n",
                    count, output.length, target.toAbsolutePath().normalize());
        }
        return MAPPER.readTree(output);
    }

    @Test
    @DisplayName("Файл является FeatureCollection в WGS 84")
    void isFeatureCollection() throws Exception {
        JsonNode root = exported();
        assertThat(root.get("type").asText()).isEqualTo("FeatureCollection");
        assertThat(root.get("crs").get("properties").get("name").asText())
                .isEqualTo("urn:ogc:def:crs:OGC:1.3:CRS84");
        assertThat(root.get("features").isArray()).isTrue();
        assertThat(root.get("features")).isNotEmpty();
    }

    @Test
    @DisplayName("У каждого типа ровно свой обязательный набор атрибутов")
    void attributeSetsAreExact() throws Exception {
        JsonNode root = exported();
        for (JsonNode feature : root.get("features")) {
            JsonNode props = feature.get("properties");
            String type = props.get("object_type").asText();
            Set<String> required = REQUIRED.get(type);
            assertThat(required).as("неизвестный тип выходного объекта: %s", type).isNotNull();

            Set<String> actual = new LinkedHashSet<>();
            props.fieldNames().forEachRemaining(actual::add);

            assertThat(actual)
                    .as("состав атрибутов объекта %s типа %s",
                            props.get("id").asText(), type)
                    .containsExactlyInAnyOrderElementsOf(required);
        }
    }

    @Test
    @DisplayName("На каждый вариант ровно одна сводная запись с пустой геометрией")
    void summaryPerVariant() throws Exception {
        JsonNode root = exported();
        Map<String, Integer> summaries = new LinkedHashMap<>();
        for (JsonNode feature : root.get("features")) {
            JsonNode props = feature.get("properties");
            if (!"variant_summary".equals(props.get("object_type").asText())) {
                continue;
            }
            summaries.merge(props.get("variant_id").asText(), 1, Integer::sum);
            assertThat(feature.get("geometry").isNull())
                    .as("сводная запись не является пространственным объектом").isTrue();
        }
        assertThat(summaries).hasSize(plan.getVariants().size());
        assertThat(summaries.values()).allMatch(c -> c == 1);
    }

    @Test
    @DisplayName("Координаты вернулись в WGS 84 и лежат в границах исходного набора")
    void coordinatesAreGeographic() throws Exception {
        JsonNode root = exported();
        int checked = 0;
        for (JsonNode feature : root.get("features")) {
            JsonNode geometry = feature.get("geometry");
            if (geometry.isNull()) {
                continue;
            }
            for (JsonNode c : flatten(geometry.get("coordinates"))) {
                double lon = c.get(0).asDouble();
                double lat = c.get(1).asDouble();
                assertThat(lon).isBetween(37.60, 37.68);
                assertThat(lat).isBetween(55.68, 55.72);
                checked++;
            }
        }
        assertThat(checked).isGreaterThan(100);
    }

    @Test
    @DisplayName("Ссылки между объектами разрешаются внутри своего варианта")
    void referencesResolve() throws Exception {
        JsonNode root = exported();
        Map<String, Set<String>> nodesByVariant = new LinkedHashMap<>();
        Map<String, Set<String>> edgeEndsByVariant = new LinkedHashMap<>();

        for (JsonNode feature : root.get("features")) {
            JsonNode props = feature.get("properties");
            String type = props.get("object_type").asText();
            String variant = props.get("variant_id").asText();
            switch (type) {
                case "heat_chamber":
                case "technical_node":
                case "tie_in":
                    nodesByVariant.computeIfAbsent(variant, k -> new LinkedHashSet<>())
                            .add(props.get("id").asText());
                    break;
                case "heat_network":
                    edgeEndsByVariant.computeIfAbsent(variant, k -> new LinkedHashSet<>())
                            .add(props.get("start_node_id").asText());
                    edgeEndsByVariant.get(variant).add(props.get("end_node_id").asText());
                    break;
                default:
                    break;
            }
        }

        // Конец участка ссылается либо на узел этого же варианта, либо на объект
        // входного файла: точку подключения ОКС и существующую камеру, использованную
        // под врезку, раздел 10 ТП повторно не выгружает. Поэтому разрешение идёт
        // против объединения выходных узлов и входных идентификаторов.
        Set<String> fromInput = new LinkedHashSet<>();
        scene.getChambers().forEach(ch -> fromInput.add(ch.getId()));
        scene.getFutureOks().forEach(o -> fromInput.add(o.getConnectionPointId()));

        for (Map.Entry<String, Set<String>> e : edgeEndsByVariant.entrySet()) {
            Set<String> known = new LinkedHashSet<>(nodesByVariant.getOrDefault(e.getKey(), Set.of()));
            known.addAll(fromInput);
            assertThat(e.getValue())
                    .as("вариант %s: концы участков должны разрешаться", e.getKey())
                    .allMatch(known::contains);
        }
    }

    @Test
    @DisplayName("Собственный парсер читает собственную выгрузку")
    void ownParserReadsOwnOutput() throws Exception {
        exported();
        SceneAssembler.Collector collector = new SceneAssembler.Collector();
        long count = parser.parse(new ByteArrayInputStream(output), collector::accept);
        assertThat(count).isGreaterThan(0);
        // Выходные типы не относятся к входным и попадают в «нераспознанные» —
        // это ожидаемо, важно, что разбор проходит без ошибок формата.
        assertThat(collector).isNotNull();
    }

    private List<JsonNode> flatten(JsonNode coordinates) {
        List<JsonNode> out = new java.util.ArrayList<>();
        if (coordinates.isArray() && coordinates.get(0).isNumber()) {
            out.add(coordinates);
        } else {
            for (JsonNode child : coordinates) {
                out.addAll(flatten(child));
            }
        }
        return out;
    }
}
