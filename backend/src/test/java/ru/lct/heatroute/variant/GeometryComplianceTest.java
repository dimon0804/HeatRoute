package ru.lct.heatroute.variant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatroute.domain.model.FutureOks;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.domain.result.CalculationVariant;
import ru.lct.heatroute.domain.result.NewSegment;
import ru.lct.heatroute.geo.GeoProperties;
import ru.lct.heatroute.ingest.GeoJsonStreamParser;
import ru.lct.heatroute.ingest.SceneAssembler;
import ru.lct.heatroute.routing.ObstacleField;
import ru.lct.heatroute.routing.RoutingProperties;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Геометрическое соответствие построенной сети требованиям кейса.
 * <p>
 * Это первое, что проверит эксперт, открыв выгрузку в ГИС: не режет ли трасса здания
 * и не пересекает ли сама себя. Тест делает ту же проверку численно, по каждому отрезку
 * каждого варианта, а не на глаз по картинке.
 */
@SpringBootTest
@ActiveProfiles("nodb")
class GeometryComplianceTest {

    @Autowired
    GeoJsonStreamParser parser;
    @Autowired
    SceneAssembler assembler;
    @Autowired
    VariantPlanner planner;
    @Autowired
    ReferenceCatalog catalog;
    @Autowired
    GeoProperties geoProps;
    @Autowired
    RoutingProperties routingProps;

    private static InputScene scene;
    private static VariantPlanner.Plan plan;

    private VariantPlanner.Plan plan() throws Exception {
        if (plan == null) {
            SceneAssembler.Collector collector = new SceneAssembler.Collector();
            try (InputStream in = getClass().getResourceAsStream("/samples/dataset_lct2026.geojson")) {
                parser.parse(Objects.requireNonNull(in), collector::accept);
            }
            scene = assembler.assemble(collector);
            plan = planner.plan(scene);
        }
        return plan;
    }

    @Test
    @DisplayName("Новая трасса не заходит в чужие здания и выдерживает клиренс")
    void respectsClearances() throws Exception {
        VariantPlanner.Plan p = plan();
        ObstacleField field = new ObstacleField(scene, catalog, geoProps, routingProps);

        // Точка подключения ОКС лежит внутри его собственного здания: последнее звено
        // трассы заходит туда к ИТП. Для такого звена собственный контур исключается,
        // все прочие препятствия действуют в полную силу.
        Map<String, String> oksByConnectionPoint = new LinkedHashMap<>();
        for (FutureOks oks : scene.getFutureOks()) {
            oksByConnectionPoint.put(oks.getConnectionPointId(), oks.getId());
        }

        List<String> violations = new ArrayList<>();

        for (CalculationVariant variant : p.getVariants()) {
            for (NewSegment segment : variant.getSegments()) {
                String exempt = oksByConnectionPoint.getOrDefault(
                        segment.getEndNodeId(),
                        oksByConnectionPoint.get(segment.getStartNodeId()));

                Coordinate[] coords = segment.getGeometry().getCoordinates();
                for (int i = 0; i + 1 < coords.length; i++) {
                    ObstacleField.Obstacle blocker = field.blockingObstacle(
                            coords[i], coords[i + 1], segment.getDiameter(), exempt);
                    if (blocker != null) {
                        violations.add(String.format(
                                "вариант %s, участок %s (ДУ %d): звено %d входит в объект %s (%s)",
                                variant.getVariantId(), segment.getId(), segment.getDiameter(),
                                i, blocker.getRestrictionId(), blocker.getCanonicalType()));
                    }
                }
            }
        }

        assertThat(violations)
                .as("нарушения минимального расстояния до пространственных ограничений")
                .isEmpty();
    }

    @Test
    @DisplayName("Новые участки не пересекаются между собой вне общего узла")
    void segmentsDoNotCross() throws Exception {
        VariantPlanner.Plan p = plan();
        List<String> crossings = new ArrayList<>();

        for (CalculationVariant variant : p.getVariants()) {
            List<NewSegment> segments = variant.getSegments();
            for (int i = 0; i < segments.size(); i++) {
                for (int j = i + 1; j < segments.size(); j++) {
                    NewSegment a = segments.get(i);
                    NewSegment b = segments.get(j);
                    if (sharesNode(a, b)) {
                        continue;   // общий узел — законное касание
                    }
                    Geometry intersection = a.getGeometry().intersection(b.getGeometry());
                    if (!intersection.isEmpty()) {
                        crossings.add(String.format("вариант %s: участки %s и %s пересекаются",
                                variant.getVariantId(), a.getId(), b.getId()));
                    }
                }
            }
        }

        assertThat(crossings)
                .as("раздел 2.3 ТЗ: новые участки не должны пересекаться вне общего узла")
                .isEmpty();
    }

    /**
     * Наименьшая средняя длина звена, при которой ломаная ещё осмысленна, м.
     * <p>
     * Критерий оценки говорит про «отсутствие случайной мелкой ломаной», то есть
     * про мелкость звеньев, а не про число углов. Считать углы оказалось неверно:
     * после того как клиренс отвода стал считаться по его собственному диаметру,
     * трасса законно проходит более узкими местами и огибает на угол-другой больше,
     * оставаясь при этом прямолинейной по звеньям. Наказывать за это нельзя —
     * альтернативой был бы обход длиннее.
     * <p>
     * Измерение на конкурсном наборе: самый дробный многозвенный участок даёт
     * 14,5 м на звено. Порог в восемь метров — почти двойной запас, он отсекает
     * вырождение, а не текущий результат.
     */
    private static final double MIN_AVERAGE_LINK_M = 8.0;

    @Test
    @DisplayName("Геометрия трассы состоит из немногих прямых звеньев без мелкой ломаной")
    void geometryIsReasonable() throws Exception {
        VariantPlanner.Plan p = plan();

        for (CalculationVariant variant : p.getVariants()) {
            for (NewSegment segment : variant.getSegments()) {
                LineString line = segment.getGeometry();
                Coordinate[] coords = line.getCoordinates();

                // Участок в несколько десятков метров, разбитый на десятки звеньев,
                // и есть та самая «случайная мелкая ломаная» из критериев оценки.
                // Проверяется на участках от трёх звеньев: короткий специальный проход
                // через пересекаемую коммуникацию — это одно звено в пару метров,
                // и оно обязано быть коротким.
                if (coords.length >= 4) {
                    double average = segment.getLength() / (coords.length - 1);
                    assertThat(average)
                            .as("участок %s: %d звеньев на %.1f м",
                                    segment.getId(), coords.length - 1, segment.getLength())
                            .isGreaterThanOrEqualTo(MIN_AVERAGE_LINK_M);
                }

                // Звенья короче полуметра не несут смысла и появляются только
                // из-за погрешностей построения.
                for (int i = 0; i + 1 < coords.length; i++) {
                    double step = coords[i].distance(coords[i + 1]);
                    assertThat(step)
                            .as("звено %d участка %s", i, segment.getId())
                            .isGreaterThan(0.5);
                }
            }
        }
    }

    @Test
    @DisplayName("Идентификаторы объектов варианта последовательны и без разрывов")
    void identifiersAreSequential() throws Exception {
        VariantPlanner.Plan p = plan();

        for (CalculationVariant variant : p.getVariants()) {
            assertSequential(variant.getSegments().stream()
                    .map(NewSegment::getId).collect(java.util.stream.Collectors.toList()),
                    "new_" + variant.getVariantId() + "_");
            assertSequential(variant.getChambers().stream()
                    .map(c -> c.getId()).collect(java.util.stream.Collectors.toList()),
                    "ch_" + variant.getVariantId() + "_");
            assertSequential(variant.getTieIns().stream()
                    .map(t -> t.getId()).collect(java.util.stream.Collectors.toList()),
                    "tie_" + variant.getVariantId() + "_");
        }
    }

    private void assertSequential(List<String> ids, String prefix) {
        for (int i = 0; i < ids.size(); i++) {
            assertThat(ids.get(i))
                    .as("идентификаторы должны идти подряд без разрывов")
                    .isEqualTo(prefix + (i + 1));
        }
    }

    private boolean sharesNode(NewSegment a, NewSegment b) {
        return a.getStartNodeId().equals(b.getStartNodeId())
                || a.getStartNodeId().equals(b.getEndNodeId())
                || a.getEndNodeId().equals(b.getStartNodeId())
                || a.getEndNodeId().equals(b.getEndNodeId());
    }
}
