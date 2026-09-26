package ru.lct.heatroute.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.result.CalculationVariant;
import ru.lct.heatroute.variant.VariantPlanner;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Вырожденные наборы: то, что подсунут проверяющие.
 * <p>
 * Конкурсный набор устроен удобно — источник на месте, сеть связна, все ОКС достижимы.
 * Проверочный может оказаться каким угодно, а сервис обязан на любом входе либо
 * посчитать, либо внятно объяснить, почему не может. Падение с исключением
 * и пустой экран одинаково недопустимы.
 * <p>
 * Наборы собираются здесь же строками: смысл проверки в том, что именно этот GeoJSON
 * проходит через настоящий парсер, сборщик сцены и планировщик, а не в том,
 * что где-то лежит файл.
 */
@SpringBootTest
@ActiveProfiles("nodb")
class DegenerateInputTest {

    @Autowired
    GeoJsonStreamParser parser;
    @Autowired
    SceneAssembler assembler;
    @Autowired
    VariantPlanner planner;
    @Autowired
    ru.lct.heatroute.export.ResultFeatureFactory exportFeatures;

    /** Набор из перечисленных объектов — вокруг точки, близкой к конкурсному району. */
    private static String dataset(String... features) {
        return "{\"type\":\"FeatureCollection\",\"features\":[" + String.join(",", features) + "]}";
    }

    private static String source(int id, double lon, double lat) {
        return feature(id, "source", "", point(lon, lat));
    }

    private static String network(int id, int dn, double lon1, double lat1,
                                  double lon2, double lat2) {
        return feature(id, "heat_network", ",\"diameter\":" + dn,
                "{\"type\":\"LineString\",\"coordinates\":[[" + lon1 + "," + lat1 + "],["
                        + lon2 + "," + lat2 + "]]}");
    }

    private static String connectionPoint(int id, double flow, double lon, double lat) {
        return feature(id, "oks_connection_point", ",\"flow_tph\":" + flow, point(lon, lat));
    }

    private static String feature(int id, String type, String extra, String geometry) {
        return feature(String.valueOf(id), type, extra, geometry);
    }

    /**
     * То же, но идентификатор подставляется в JSON как есть. Нужен там, где проверяется
     * сам тип идентификатора: {@code 4} и {@code "4"} — разные входные данные, и раздел
     * 7.2 требует вернуть каждый тем же типом, каким он пришёл.
     */
    private static String feature(String jsonId, String type, String extra, String geometry) {
        return "{\"type\":\"Feature\",\"properties\":{\"id\":" + jsonId
                + ",\"object_type\":\"" + type + "\"" + extra + "},\"geometry\":" + geometry + "}";
    }

    /**
     * Кольцо-ограничение вокруг точки: внешний квадрат со стороной около 120 м
     * и отверстие около 40 м. Точка в отверстии не лежит внутри полигона, поэтому
     * собственным контуром объекта кольцо не опознаётся и работает как препятствие.
     */
    private static String ring(int id, double lon, double lat) {
        double outer = 0.0006;
        double inner = 0.0002;
        String outerRing = square(lon, lat, outer);
        String innerRing = square(lon, lat, inner);
        return feature(id, "restriction", ",\"restriction_type\":\"oks_existing\"",
                "{\"type\":\"Polygon\",\"coordinates\":[" + outerRing + "," + innerRing + "]}");
    }

    private static String square(double lon, double lat, double half) {
        return "[[" + (lon - half) + "," + (lat - half) + "],"
                + "[" + (lon + half) + "," + (lat - half) + "],"
                + "[" + (lon + half) + "," + (lat + half) + "],"
                + "[" + (lon - half) + "," + (lat + half) + "],"
                + "[" + (lon - half) + "," + (lat - half) + "]]";
    }

    private static String point(double lon, double lat) {
        return "{\"type\":\"Point\",\"coordinates\":[" + lon + "," + lat + "]}";
    }

    private InputScene scene(String json) throws Exception {
        SceneAssembler.Collector collector = new SceneAssembler.Collector();
        parser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                collector::accept);
        return assembler.assemble(collector);
    }

    @Test
    @DisplayName("Набор без объектов разбирается и не ломает расчёт")
    void emptyCollection() throws Exception {
        InputScene scene = scene(dataset());

        assertThat(scene.getFutureOks()).isEmpty();
        assertThat(scene.getSegments()).isEmpty();

        VariantPlanner.Plan plan = planner.plan(scene);
        assertThat(plan.getVariants())
                .as("подключать нечего — вариантов нет, но и падения нет")
                .isEmpty();
    }

    @Test
    @DisplayName("Набор без источника разбирается, а расчёт объясняет невозможность")
    void networkWithoutSource() throws Exception {
        InputScene scene = scene(dataset(
                network(1, 300, 37.6400, 55.7000, 37.6420, 55.7010),
                connectionPoint(2, 10.0, 37.6410, 55.7020)));

        assertThat(scene.getSource())
                .as("источника в наборе нет — сцена это признаёт, а не подставляет чужой")
                .isNull();
        assertThatCode(() -> planner.plan(scene)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Набор без единого перспективного ОКС не даёт вариантов и не падает")
    void nothingToConnect() throws Exception {
        InputScene scene = scene(dataset(
                source(1, 37.6400, 55.7000),
                network(2, 300, 37.6400, 55.7000, 37.6420, 55.7010)));

        assertThat(scene.getFutureOks()).isEmpty();
        assertThat(planner.plan(scene).getVariants()).isEmpty();
    }

    @Test
    @DisplayName("Минимальный набор — источник, участок и один ОКС — считается до конца")
    void minimalUsableDataset() throws Exception {
        InputScene scene = scene(dataset(
                source(1, 37.6400, 55.7000),
                network(2, 300, 37.6400, 55.7000, 37.6440, 55.7000),
                connectionPoint(3, 12.5, 37.6420, 55.7008)));

        VariantPlanner.Plan plan = planner.plan(scene);

        assertThat(plan.getVariants())
                .as("один объект рядом с сетью подключается")
                .isNotEmpty();
        assertThat(plan.getVariants().get(0).getSummary().getUnconnectedOksIds()).isEmpty();
        assertThat(plan.getVariants().get(0).getSegments()).isNotEmpty();
    }

    @Test
    @DisplayName("Далёкий ОКС не исчезает: он либо подключён, либо назван неподключенным")
    void distantOksIsAccountedFor() throws Exception {
        // Объект в семи километрах от сети. Подключить его дороже штрафа, но раздел 2.11
        // ТЗ требует обработать все перспективные ОКС, а штраф раздела 8.3 предусмотрен
        // для случая «маршрут не найден», а не «маршрут невыгоден». Проверяется главное:
        // объект не пропадает из результата молча.
        InputScene scene = scene(dataset(
                source(1, 37.6400, 55.7000),
                network(2, 300, 37.6400, 55.7000, 37.6440, 55.7000),
                connectionPoint(3, 12.5, 37.6420, 55.7008),
                connectionPoint(4, 8.0, 37.7200, 55.7600)));

        VariantPlanner.Plan plan = planner.plan(scene);
        assertThat(plan.getVariants())
                .as("частичный результат сохраняется (раздел 2.9 ТЗ)")
                .isNotEmpty();

        CalculationVariant best = plan.getVariants().get(0);
        boolean connected = best.getSegments().stream()
                .anyMatch(seg -> "4".equals(seg.getEndNodeId()));
        // Идентификатор в наборе числовой, поэтому и в списке неподключённых он число:
        // раздел 7.2 требует сохранять тип идентификатора из входных данных.
        assertThat(connected || best.getSummary().getUnconnectedOksIds().contains(4L))
                .as("объект 4 либо в построенной сети, либо в списке неподключенных")
                .isTrue();
    }

    @Test
    @DisplayName("Запертый ограничениями ОКС попадает в неподключенные со штрафом")
    void lockedOksGoesToUnconnected() throws Exception {
        // Кольцо-ограничение вокруг точки подключения: сама точка лежит в отверстии,
        // поэтому собственным контуром объекта кольцо не считается — оно остаётся
        // препятствием, и маршрута к объекту нет.
        InputScene scene = scene(dataset(
                source(1, 37.6400, 55.7000),
                network(2, 300, 37.6400, 55.7000, 37.6440, 55.7000),
                connectionPoint(3, 12.5, 37.6420, 55.7008),
                connectionPoint(4, 8.0, 37.6480, 55.7008),
                ring(5, 37.6480, 55.7008)));

        VariantPlanner.Plan plan = planner.plan(scene);

        assertThat(plan.getVariants())
                .as("построенная часть сохраняется, несмотря на запертый объект")
                .isNotEmpty();

        CalculationVariant best = plan.getVariants().get(0);
        assertThat(best.getSummary().getUnconnectedOksIds())
                .as("запертый объект назван поимённо, числом — как во входном файле")
                .contains(4L);
        assertThat(best.getSummary().getUnconnectedPenalty())
                .as("за неподключенный объект начислен штраф раздела 8.3 ТП")
                .isPositive();
        assertThat(best.getSegments())
                .as("остальная сеть построена")
                .isNotEmpty();

        // Список идентификаторов без объяснения бесполезен: раздел 2.9 требует
        // обработать случай, а обработать — значит сказать, что с объектом не так.
        assertThat(best.getSummary().getUnconnectedReasons())
                .as("у каждого неподключенного объекта есть причина")
                .containsKey("4");
        assertThat(best.getSummary().getUnconnectedReasons().get("4"))
                .as("причина написана по-русски и объясняет, а не кодирует")
                .isNotBlank()
                .hasSizeGreaterThan(20);

        System.out.printf("%nОКС 4 не подключён: %s%n%n",
                best.getSummary().getUnconnectedReasons().get("4"));
    }

    @Test
    @DisplayName("unconnected_oks_ids отдаёт идентификатор тем же типом, каким он пришёл")
    void unconnectedIdsKeepInputType() throws Exception {
        // Точка 4 заперта кольцом-ограничением, как в предыдущей проверке: она заведомо
        // окажется в списке неподключённых, и на ней видно, каким типом уходит её ID.
        InputScene numeric = scene(dataset(
                source(1, 37.6400, 55.7000),
                network(2, 300, 37.6400, 55.7000, 37.6440, 55.7000),
                connectionPoint(3, 12.5, 37.6420, 55.7008),
                connectionPoint(4, 8.0, 37.6480, 55.7008),
                ring(5, 37.6480, 55.7008)));

        assertThat(exportedUnconnectedIds(numeric))
                .as("во входном файле идентификатор был числом — числом он и уходит")
                .contains(4L)
                .doesNotContain("4");

        // Тот же набор, но идентификатор запертой точки строковый.
        InputScene textual = scene(dataset(
                source(1, 37.6400, 55.7000),
                network(2, 300, 37.6400, 55.7000, 37.6440, 55.7000),
                connectionPoint(3, 12.5, 37.6420, 55.7008),
                feature("\"p-4\"", "oks_connection_point", ",\"flow_tph\":8.0",
                        point(37.6480, 55.7008)),
                ring(5, 37.6480, 55.7008)));

        assertThat(exportedUnconnectedIds(textual))
                .as("строковый идентификатор не превращается в число")
                .contains("p-4");
    }

    /** Значение {@code unconnected_oks_ids} в сводной записи лучшего варианта. */
    @SuppressWarnings("unchecked")
    private List<Object> exportedUnconnectedIds(InputScene scene) {
        CalculationVariant best = planner.plan(scene).getVariants().get(0);
        return (List<Object>) exportFeatures.summary(best.getSummary())
                .getProperties().get("unconnected_oks_ids");
    }

    @Test
    @DisplayName("Расход больше наибольшего диаметра справочника не роняет расчёт")
    void flowBeyondCatalog() throws Exception {
        InputScene scene = scene(dataset(
                source(1, 37.6400, 55.7000),
                network(2, 300, 37.6400, 55.7000, 37.6440, 55.7000),
                connectionPoint(3, 99_999.0, 37.6420, 55.7008)));

        assertThatCode(() -> planner.plan(scene))
                .as("расход вне таблицы 4.1 — повод для диагностики, а не для исключения")
                .doesNotThrowAnyException();
    }
}
