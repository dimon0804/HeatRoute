package ru.lct.heatroute.variant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.result.CalculationVariant;
import ru.lct.heatroute.domain.result.NewSegment;
import ru.lct.heatroute.geo.Geo;
import ru.lct.heatroute.ingest.GeoJsonStreamParser;
import ru.lct.heatroute.ingest.SceneAssembler;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Запретные зоны, задаваемые на запуск расчёта.
 * <p>
 * Проверяется то, ради чего они сделаны: трасса, проходившая через место, после запрета
 * через него не идёт, а подключение объектов при этом не теряется. Зона строится вокруг
 * точки, по которой трасса шла в обычном расчёте, — иначе запрет ничего не значил бы.
 */
@SpringBootTest
@ActiveProfiles("nodb")
class ForbiddenZoneTest {

    /** Радиус запретной зоны: заметный на местности, но не перекрывающий квартал. */
    private static final double ZONE_RADIUS_M = 35;

    @Autowired
    GeoJsonStreamParser parser;
    @Autowired
    SceneAssembler assembler;
    @Autowired
    VariantPlanner planner;

    private static InputScene scene;
    private static VariantPlanner.Plan baseline;

    @BeforeEach
    void prepare() throws Exception {
        if (baseline != null) {
            return;
        }
        SceneAssembler.Collector collector = new SceneAssembler.Collector();
        try (InputStream in = getClass().getResourceAsStream("/samples/dataset_lct2026.geojson")) {
            parser.parse(Objects.requireNonNull(in), collector::accept);
        }
        scene = assembler.assemble(collector);
        baseline = planner.plan(scene);
    }

    @Test
    @DisplayName("Трасса обходит запретную зону, поставленную на её пути")
    void routeAvoidsForbiddenZone() {
        CalculationVariant best = baseline.getVariants().get(0);
        NewSegment longest = best.getSegments().stream()
                .max(java.util.Comparator.comparingDouble(NewSegment::getLength))
                .orElseThrow(() -> new AssertionError("в лучшем варианте нет участков"));

        // Зона ставится на середину самого длинного участка лучшего варианта: место,
        // через которое трасса заведомо шла.
        Coordinate middle = Geo.substring(longest.getGeometry(), 0.5, 0.5).getCoordinateN(0);
        Geometry zone = Geo.point(middle).buffer(ZONE_RADIUS_M);

        VariantPlanner.Plan restricted = planner.plan(scene, VariantPlanner.Progress.NONE,
                VariantPlanner.Options.builder().forbiddenZones(List.of(zone)).build());

        assertThat(restricted.getVariants())
                .as("запрет не должен оставлять расчёт без вариантов")
                .isNotEmpty();

        for (CalculationVariant variant : restricted.getVariants()) {
            for (NewSegment segment : variant.getSegments()) {
                assertThat(zone.intersects(segment.getGeometry()))
                        .as("участок %s варианта %s проходит через запретную зону",
                                segment.getId(), variant.getVariantId())
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("Обход зоны не стоит подключения объектов")
    void detourKeepsObjectsConnected() {
        CalculationVariant best = baseline.getVariants().get(0);
        NewSegment longest = best.getSegments().stream()
                .max(java.util.Comparator.comparingDouble(NewSegment::getLength))
                .orElseThrow(() -> new AssertionError("в лучшем варианте нет участков"));
        Coordinate middle = Geo.substring(longest.getGeometry(), 0.5, 0.5).getCoordinateN(0);

        VariantPlanner.Plan restricted = planner.plan(scene, VariantPlanner.Progress.NONE,
                VariantPlanner.Options.builder()
                        .forbiddenZones(List.of(Geo.point(middle).buffer(ZONE_RADIUS_M)))
                        .build());

        CalculationVariant bestRestricted = restricted.getVariants().get(0);
        assertThat(bestRestricted.getSummary().getUnconnectedOksIds())
                .as("обход одной зоны не должен оставлять ОКС без подключения")
                .isEmpty();

        System.out.printf("%nЗапретная зона R=%.0f м: S %.3f → %.3f, сеть %.1f → %.1f м%n%n",
                ZONE_RADIUS_M, best.getSummary().getScore(),
                bestRestricted.getSummary().getScore(),
                best.getSummary().getNewNetworkLength(),
                bestRestricted.getSummary().getNewNetworkLength());
    }

    @Test
    @DisplayName("Заданный условный диаметр применяется к расчёту клиренсов")
    void designDiameterFromRequestIsApplied() {
        VariantPlanner.Plan strict = planner.plan(scene, VariantPlanner.Progress.NONE,
                VariantPlanner.Options.builder().designDiameter(600).build());

        assertThat(strict.getDesignDiameter())
                .as("диаметр из запроса важнее подобранного по суммарному расходу")
                .isEqualTo(600);
        assertThat(baseline.getDesignDiameter())
                .as("без указания диаметр подбирается по расходу")
                .isEqualTo(400);
    }
}
