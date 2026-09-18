package ru.lct.heatroute.variant;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatroute.domain.result.NewSegment;
import ru.lct.heatroute.geo.Geo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Запреты, которые действуют не для всей сцены, а для одного построения.
 * <p>
 * Постоянные препятствия живут в {@link ru.lct.heatroute.routing.ObstacleField}: они
 * одинаковы для всех вариантов и попадают в граф видимости при его построении. Запреты
 * этого класса появляются по ходу перебора и действуют только на одну часть сети:
 * <ul>
 *   <li>уже принятые части того же варианта — раздел 2.3 ТЗ запрещает пересечения новых
 *       участков вне общего узла, а объединить независимые части нельзя: у каждой своя
 *       точка врезки, и общий узел дал бы два пути до одного ОКС вопреки разделу 2.2 ТЗ;</li>
 *   <li>зоны, где манёвр по глубине не помещается — профиль сообщает маршрутизации,
 *       что пересечение нужно перенести туда, где под заглубление хватает разбега.</li>
 * </ul>
 * Барьер передаётся в проверку проходимости рядом с обычными препятствиями, поэтому
 * трасса обходит его так же, как обходит здание, — на этапе построения, а не правкой
 * готового результата.
 */
public final class RouteBarrier {

    /** Ничего не запрещено: первый проход, когда принятых частей и зон ещё нет. */
    public static final RouteBarrier NONE = new RouteBarrier(Collections.emptyList());

    /**
     * Насколько отрезок укорачивается с концов перед проверкой. Концы трасс разных
     * частей могут сходиться в одной точке существующей камеры — это примыкание,
     * а не пересечение, и запрещать его нельзя.
     */
    private static final double END_EPSILON_M = 0.05;

    private final List<Geometry> parts;
    private final STRtree index = new STRtree();
    private final boolean empty;

    private RouteBarrier(List<Geometry> parts) {
        this.parts = parts;
        this.empty = parts.isEmpty();
        for (Geometry g : parts) {
            index.insert(g.getEnvelopeInternal(), g);
        }
        if (!empty) {
            index.build();
        }
    }

    /** Уже принятые части сети этого варианта. */
    public static RouteBarrier ofAcceptedParts(List<NewSegment> accepted) {
        if (accepted.isEmpty()) {
            return NONE;
        }
        return new RouteBarrier(accepted.stream()
                .map(s -> (Geometry) s.getGeometry())
                .collect(Collectors.toList()));
    }

    /** Зоны, через которые трассе проходить нельзя. */
    public static RouteBarrier ofZones(List<? extends Geometry> zones) {
        return zones.isEmpty() ? NONE : new RouteBarrier(new ArrayList<>(zones));
    }

    /** Оба набора запретов сразу. */
    public RouteBarrier plus(RouteBarrier other) {
        if (other.empty) {
            return this;
        }
        if (this.empty) {
            return other;
        }
        List<Geometry> merged = new ArrayList<>(parts.size() + other.parts.size());
        merged.addAll(parts);
        merged.addAll(other.parts);
        return new RouteBarrier(merged);
    }

    public boolean isEmpty() {
        return empty;
    }

    /** Пересекает ли прямой отрезок хотя бы один запрет. */
    @SuppressWarnings("unchecked")
    public boolean blocks(Coordinate a, Coordinate b) {
        if (empty) {
            return false;
        }
        // Проверка вызывается на каждое ребро обхода — сотни тысяч раз за расчёт.
        // Сначала охват: если рядом нет ни одного запрета, геометрия не строится вовсе.
        List<Geometry> candidates = index.query(new Envelope(a, b));
        if (candidates.isEmpty()) {
            return false;
        }
        LineString probe = trimmed(a, b);
        if (probe == null) {
            return false;
        }
        for (Geometry other : candidates) {
            if (probe.intersects(other)) {
                return true;
            }
        }
        return false;
    }

    private static LineString trimmed(Coordinate a, Coordinate b) {
        double len = a.distance(b);
        if (len <= 2 * END_EPSILON_M) {
            return null;
        }
        double t = END_EPSILON_M / len;
        Coordinate a1 = new Coordinate(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
        Coordinate b1 = new Coordinate(b.x - (b.x - a.x) * t, b.y - (b.y - a.y) * t);
        return Geo.line(a1, b1);
    }
}
