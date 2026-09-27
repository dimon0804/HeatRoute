package ru.lct.heatroute.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.model.RestrictionObject;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.domain.reference.RestrictionRule;
import ru.lct.heatroute.geo.GeoProperties;
import ru.lct.heatroute.ingest.GeoJsonStreamParser;
import ru.lct.heatroute.ingest.SceneAssembler;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Минимальное горизонтальное расстояние рядом со специальным объектом (раздел 4 ТП,
 * разъяснение №7): дорогу пересечь можно, а идти вдоль неё ближе минимального
 * расстояния — нельзя, и внутри самого разрешённого пересечения отступ не проверяется.
 * <p>
 * Обстановка здесь искусственная и разобрана до метра: одна дорога-прямоугольник,
 * известный отступ по таблице 2 и пробные отрезки, поставленные относительно её границ.
 * Проверять это правило на построенной трассе бессмысленно — трассу строит тот же
 * {@link ObstacleField}, и она пройдёт проверку по определению, каким бы правило ни было.
 */
@SpringBootTest
@ActiveProfiles("nodb")
class SpecialClearanceTest {

    /** ДУ пробной трассы: от него зависит половина габарита пары труб в отступе. */
    private static final int DN = 100;

    /** Дорога-прямоугольник: 100 м вдоль, 40 м поперёк. */
    private static final double ROAD_LENGTH_M = 100;
    private static final double ROAD_WIDTH_M = 40;

    @Autowired
    GeoJsonStreamParser parser;
    @Autowired
    SceneAssembler assembler;
    @Autowired
    ReferenceCatalog catalog;
    @Autowired
    GeoProperties geoProps;
    @Autowired
    RoutingProperties routingProps;

    @Test
    @DisplayName("Проход вдоль специального объекта длиннее допуска отвергается")
    void alongsideRunLongerThanToleranceIsRejected() throws Exception {
        Field field = roadField();
        double offset = field.clearance / 2;          // заведомо внутри зоны отступа
        double run = routingProps.getSpecialAlongsideToleranceM() * 5;

        Coordinate from = new Coordinate(field.road.getMinX() + 5,
                field.road.getMaxY() + offset);
        Coordinate to = new Coordinate(from.x + run, from.y);

        ObstacleField.Obstacle blocker = field.obstacles.blockingObstacle(from, to, DN, null);

        assertThat(blocker)
                .as("%.0f м вдоль дороги в %.2f м от неё при отступе %.2f м — нарушение",
                        run, offset, field.clearance)
                .isNotNull();
        assertThat(blocker.getCanonicalType()).isEqualTo("road");
    }

    @Test
    @DisplayName("Короткое касание зоны отступа сходит за неизбежное примыкание")
    void shortTouchOfTheClearanceZoneIsTolerated() throws Exception {
        Field field = roadField();
        double offset = field.clearance / 2;
        double run = routingProps.getSpecialAlongsideToleranceM() / 3;

        Coordinate from = new Coordinate(field.road.getMinX() + 5,
                field.road.getMaxY() + offset);
        Coordinate to = new Coordinate(from.x + run, from.y);

        assertThat(field.obstacles.blockingObstacle(from, to, DN, null))
                .as("допуск на примыкание существует именно для таких отрезков: выход "
                        + "из места присоединения задевает зону на считанные метры")
                .isNull();
    }

    @Test
    @DisplayName("Внутри разрешённого пересечения отступ не применяется")
    void clearanceIsNotAppliedInsideAllowedCrossing() throws Exception {
        Field field = roadField();
        double tail = routingProps.getSpecialAlongsideToleranceM() / 3;

        // Отрезок пересекает дорогу насквозь: сорок метров он идёт внутри неё и выходит
        // за границы на короткие хвосты. Если отступ считать и внутри дороги, сорок метров
        // хода перекроют любой допуск и пересечь дорогу станет нельзя нигде.
        Coordinate from = new Coordinate((field.road.getMinX() + field.road.getMaxX()) / 2,
                field.road.getMinY() - tail);
        Coordinate to = new Coordinate(from.x, field.road.getMaxY() + tail);

        assertThat(from.distance(to))
                .as("отрезок действительно длиннее допуска — иначе проверка ничего "
                        + "не доказывает")
                .isGreaterThan(routingProps.getSpecialAlongsideToleranceM() * 5);
        assertThat(field.obstacles.blockingObstacle(from, to, DN, null))
                .as("пересечение дороги специальным проходом разрешено разделом 4 ТП")
                .isNull();
    }

    @Test
    @DisplayName("Отступ снимается только на пересечении, а не на всём отрезке")
    void crossingDoesNotExcuseTheRestOfTheSegment() throws Exception {
        Field field = roadField();

        // Отрезок входит в дорогу у её длинной границы и выходит наружу под пять
        // градусов: он и пересекает дорогу, и потом долго идёт вдоль неё внутри зоны
        // отступа. Пересечение разрешено, дальнейший проход рядом — нет.
        double slope = Math.toRadians(5);
        Coordinate from = new Coordinate(field.road.getMinX() + 5,
                field.road.getMaxY() - 0.3);
        Coordinate to = new Coordinate(from.x + 40 * Math.cos(slope),
                from.y + 40 * Math.sin(slope));

        double alongside = (field.clearance - 0.3) / Math.tan(slope);
        assertThat(alongside)
                .as("проход вдоль дороги вне пересечения должен быть заметно длиннее "
                        + "и выноса специального участка, и допуска на примыкание")
                .isGreaterThan(field.specialMargin
                        + routingProps.getSpecialAlongsideToleranceM() * 3);

        assertThat(field.obstacles.blockingObstacle(from, to, DN, null))
                .as("один переход через дорогу не разрешает идти вдоль неё %.0f м "
                        + "в пределах отступа %.2f м", alongside, field.clearance)
                .isNotNull();
    }

    @Test
    @DisplayName("Выключатель проверки отступа снимает её целиком")
    void clearanceCheckCanBeTurnedOff() throws Exception {
        RoutingProperties relaxed = relaxedCopy();
        Field field = roadField(relaxed);
        double offset = field.clearance / 2;
        double run = relaxed.getSpecialAlongsideToleranceM() * 5;

        Coordinate from = new Coordinate(field.road.getMinX() + 5,
                field.road.getMaxY() + offset);
        Coordinate to = new Coordinate(from.x + run, from.y);

        assertThat(field.obstacles.blockingObstacle(from, to, DN, null))
                .as("на плотном наборе подключить ОКС с оговоркой полезнее, чем не "
                        + "подключить вовсе, и выключатель обязан работать")
                .isNull();
    }

    // =================================================================================

    /** Поле препятствий с одной дорогой: её габарит в рабочей проекции и отступ по ДУ. */
    private static final class Field {
        ObstacleField obstacles;
        Envelope road;
        double clearance;
        double specialMargin;
    }

    private Field roadField() throws Exception {
        return roadField(routingProps);
    }

    private Field roadField(RoutingProperties props) throws Exception {
        InputScene scene = scene(roadDataset());
        RestrictionObject road = scene.getRestrictions().stream()
                .filter(r -> "road".equals(r.getCanonicalType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "дорога должна опознаваться как специальный проход таблицы 2"));

        assertThat(road.getRule().getRule())
                .as("проверяется правило специального прохода, а не запрета")
                .isEqualTo(RestrictionRule.SPECIAL_CROSSING);

        Field field = new Field();
        field.obstacles = new ObstacleField(scene, catalog, geoProps, props);
        field.road = road.getGeometry().getEnvelopeInternal();
        field.clearance = catalog.clearanceBuffer(road.getRule(), DN);
        field.specialMargin = road.getRule().getSpecialMarginM();
        return field;
    }

    /** Те же настройки, но с выключенной проверкой отступа. */
    private RoutingProperties relaxedCopy() {
        RoutingProperties relaxed = new RoutingProperties();
        relaxed.setSpecialAlongsideToleranceM(routingProps.getSpecialAlongsideToleranceM());
        relaxed.setEnforceSpecialClearance(false);
        return relaxed;
    }

    /**
     * Дорога прямоугольником рядом с конкурсным районом. Размеры задаются в метрах
     * и переводятся в градусы по месту: проверка опирается на габарит уже в проекции.
     */
    private static String roadDataset() {
        double lon = 37.6400;
        double lat = 55.7000;
        double metersPerDegreeLat = 111_320;
        double metersPerDegreeLon = metersPerDegreeLat * Math.cos(Math.toRadians(lat));
        double dLon = ROAD_LENGTH_M / metersPerDegreeLon;
        double dLat = ROAD_WIDTH_M / metersPerDegreeLat;

        String ring = "[[" + lon + "," + lat + "],"
                + "[" + (lon + dLon) + "," + lat + "],"
                + "[" + (lon + dLon) + "," + (lat + dLat) + "],"
                + "[" + lon + "," + (lat + dLat) + "],"
                + "[" + lon + "," + lat + "]]";
        return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\","
                + "\"properties\":{\"id\":\"r1\",\"object_type\":\"restriction\","
                + "\"restriction_type\":\"road\"},"
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[" + ring + "]}}]}";
    }

    private InputScene scene(String json) throws Exception {
        SceneAssembler.Collector collector = new SceneAssembler.Collector();
        parser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                collector::accept);
        return assembler.assemble(collector);
    }
}
