package ru.lct.heatroute.variant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatroute.domain.result.NewSegment;
import ru.lct.heatroute.geo.Geo;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Запреты, действующие на одно построение: уже принятые части сети и зоны, где манёвр
 * по глубине не помещается. Проверяется само правило — что считается пересечением,
 * а что законным примыканием в общем узле.
 */
class RouteBarrierTest {

    private static NewSegment segment(double x1, double y1, double x2, double y2) {
        return NewSegment.builder()
                .id("s")
                .geometry(Geo.line(new Coordinate(x1, y1), new Coordinate(x2, y2)))
                .build();
    }

    @Test
    @DisplayName("Пустой барьер не запрещает ничего")
    void emptyBarrierAllowsEverything() {
        assertThat(RouteBarrier.NONE.isEmpty()).isTrue();
        assertThat(RouteBarrier.NONE.blocks(new Coordinate(0, 0), new Coordinate(100, 100)))
                .isFalse();
        assertThat(RouteBarrier.ofAcceptedParts(List.of()).isEmpty()).isTrue();
        assertThat(RouteBarrier.ofZones(List.of()).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Отрезок, пересекающий принятый участок, запрещён")
    void crossingSegmentIsBlocked() {
        RouteBarrier barrier = RouteBarrier.ofAcceptedParts(List.of(segment(0, 50, 100, 50)));

        assertThat(barrier.blocks(new Coordinate(50, 0), new Coordinate(50, 100)))
                .as("трасса пересекает принятый участок поперёк")
                .isTrue();
        assertThat(barrier.blocks(new Coordinate(50, 0), new Coordinate(50, 40)))
                .as("трасса не доходит до принятого участка")
                .isFalse();
    }

    @Test
    @DisplayName("Примыкание в общем узле пересечением не считается")
    void touchingAtSharedNodeIsAllowed() {
        // Раздел 2.3 ТЗ запрещает пересечения вне общего узла. Две части сети могут
        // сойтись в одной существующей камере — концы отрезков совпадают, и запрещать
        // это нельзя, иначе врезка в общую камеру станет невозможной.
        RouteBarrier barrier = RouteBarrier.ofAcceptedParts(List.of(segment(0, 0, 100, 0)));

        assertThat(barrier.blocks(new Coordinate(100, 0), new Coordinate(100, 80)))
                .as("отрезок начинается там, где кончается принятый участок")
                .isFalse();
    }

    @Test
    @DisplayName("Зона запрета не пропускает трассу сквозь себя")
    void zoneIsImpassable() {
        RouteBarrier barrier = RouteBarrier.ofZones(
                List.of(Geo.point(new Coordinate(50, 50)).buffer(10)));

        assertThat(barrier.blocks(new Coordinate(0, 50), new Coordinate(100, 50)))
                .as("трасса идёт прямо через зону")
                .isTrue();
        assertThat(barrier.blocks(new Coordinate(0, 20), new Coordinate(100, 20)))
                .as("трасса обходит зону стороной")
                .isFalse();
    }

    @Test
    @DisplayName("Объединённый барьер запрещает и то, и другое")
    void mergedBarrierKeepsBothSources() {
        RouteBarrier parts = RouteBarrier.ofAcceptedParts(List.of(segment(0, 50, 100, 50)));
        RouteBarrier zones = RouteBarrier.ofZones(
                List.of(Geo.point(new Coordinate(200, 0)).buffer(10)));
        RouteBarrier both = parts.plus(zones);

        assertThat(both.blocks(new Coordinate(50, 0), new Coordinate(50, 100))).isTrue();
        assertThat(both.blocks(new Coordinate(150, 0), new Coordinate(250, 0))).isTrue();
        assertThat(both.blocks(new Coordinate(0, 200), new Coordinate(100, 200))).isFalse();
        assertThat(parts.plus(RouteBarrier.NONE)).isSameAs(parts);
        assertThat(RouteBarrier.NONE.plus(zones)).isSameAs(zones);
    }
}
