package ru.lct.heatroute.routing;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.model.RestrictionObject;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.domain.reference.ReferenceProperties.RestrictionRow;
import ru.lct.heatroute.domain.reference.RestrictionRule;
import ru.lct.heatroute.geo.Geo;
import ru.lct.heatroute.geo.GeoProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Свободное пространство: что нельзя пересекать, что можно пересечь специальным
 * проходом и во что это обходится.
 * <p>
 * Клиренс зависит от условного диаметра трассы (для существующих ОКС — 5, 7 или 9 м
 * по таблице 5.1, плюс половина расчётной ширины пары труб по таблице 4.2), а диаметр
 * заранее неизвестен. Поэтому поле препятствий строится по запрошенному ДУ и кешируется:
 * значений диаметра в справочнике всего восемнадцать, а буферизация выполняется один раз
 * на диаметр и переиспользуется всеми вариантами.
 * <p>
 * Объединение буферов в одну геометрию сознательно не делается. Отдельные буферы
 * в пространственном индексе позволяют исключить из проверки собственный контур
 * подключаемого ОКС: труба заходит в своё здание к ИТП, а для чужих трасс это здание
 * остаётся обычным препятствием с полным клиренсом.
 */
@Slf4j
public class ObstacleField {

    /** Один буферизованный объект. */
    @Getter
    public static class Obstacle {
        private final String restrictionId;
        private final String canonicalType;
        private final String ownerOksId;
        private final RestrictionRow rule;
        private final Geometry source;
        private final Geometry buffered;
        private final PreparedGeometry preparedBuffered;
        private final PreparedGeometry preparedSource;

        Obstacle(RestrictionObject r, Geometry buffered) {
            this.restrictionId = r.getId();
            this.canonicalType = r.getCanonicalType();
            this.ownerOksId = r.getOwnerOksId();
            this.rule = r.getRule();
            this.source = r.getGeometry();
            this.buffered = buffered;
            this.preparedBuffered = PreparedGeometryFactory.prepare(buffered);
            this.preparedSource = PreparedGeometryFactory.prepare(r.getGeometry());
        }

        public boolean isForbidden() {
            return rule.getRule() == RestrictionRule.FORBIDDEN;
        }
    }

    private final ReferenceCatalog catalog;
    private final GeoProperties geoProps;
    private final RoutingProperties routingProps;
    private final List<RestrictionObject> restrictions;

    /** Поля препятствий по условному диаметру: буферы + индекс. */
    private final Map<Integer, Layer> layers = new HashMap<>();

    /** Фабрика без модели точности — для одноразовых отрезков-проб. */
    private static final org.locationtech.jts.geom.GeometryFactory RAW =
            new org.locationtech.jts.geom.GeometryFactory();

    private static class Layer {
        final List<Obstacle> forbidden = new ArrayList<>();
        final List<Obstacle> special = new ArrayList<>();
        final STRtree forbiddenIndex = new STRtree();
        final STRtree specialIndex = new STRtree();
    }

    public ObstacleField(InputScene scene,
                         ReferenceCatalog catalog,
                         GeoProperties geoProps,
                         RoutingProperties routingProps) {
        this.catalog = catalog;
        this.geoProps = geoProps;
        this.routingProps = routingProps;
        this.restrictions = scene.getRestrictions();
    }

    // =================================================================================
    //  Построение слоя под условный диаметр
    // =================================================================================

    private synchronized Layer layer(int dn) {
        return layers.computeIfAbsent(dn, this::buildLayer);
    }

    private Layer buildLayer(int dn) {
        long started = System.nanoTime();
        Layer layer = new Layer();
        int quadrants = geoProps.getBufferQuadrantSegments();
        double simplify = geoProps.getSimplifyTolerance();

        for (RestrictionObject r : restrictions) {
            Geometry geometry = r.getGeometry();
            if (geometry == null || geometry.isEmpty()) {
                continue;
            }
            if (simplify > 0) {
                // Упрощение сохраняет топологию: контур не выворачивается и не теряет дыр.
                Geometry simplified = TopologyPreservingSimplifier.simplify(geometry, simplify);
                if (simplified != null && !simplified.isEmpty()) {
                    geometry = simplified;
                }
            }
            if (r.getRule().getRule() == RestrictionRule.FORBIDDEN) {
                double clearance = catalog.clearanceBuffer(r.getRule(), dn);
                Geometry buffered = geometry.buffer(clearance, quadrants);
                Obstacle o = new Obstacle(r, buffered);
                layer.forbidden.add(o);
                layer.forbiddenIndex.insert(buffered.getEnvelopeInternal(), o);
            } else {
                // Специальный проход: пересекать можно, а идти рядом — не ближе
                // минимального горизонтального расстояния. Буфер нужен для проверки
                // именно этого, поэтому он строится, но не запрещает пересечение.
                double clearance = catalog.clearanceBuffer(r.getRule(), dn);
                Geometry buffered = geometry.buffer(clearance, quadrants);
                Obstacle o = new Obstacle(r, buffered);
                layer.special.add(o);
                layer.specialIndex.insert(buffered.getEnvelopeInternal(), o);
            }
        }
        layer.forbiddenIndex.build();
        layer.specialIndex.build();

        log.debug("Поле препятствий для ДУ {}: запрещённых {}, специальных {}, {} мс",
                dn, layer.forbidden.size(), layer.special.size(),
                (System.nanoTime() - started) / 1_000_000);
        return layer;
    }

    // =================================================================================
    //  Проходимость
    // =================================================================================

    /**
     * Проходим ли прямой отрезок для трассы условного диаметра {@code dn}.
     *
     * @param exemptOksId ID перспективного ОКС, чей собственный контур не учитывается;
     *                    {@code null}, если исключений нет
     */
    public boolean isPassable(Coordinate a, Coordinate b, int dn, String exemptOksId) {
        return blockingObstacle(a, b, dn, exemptOksId) == null;
    }

    /**
     * Отрезок, укороченный с концов и готовый к проверкам. Строится один раз на пару
     * узлов и передаётся во все проверки: при построении графа таких пар сотни тысяч,
     * и пересоздание геометрии на каждую проверку обходится дороже самих проверок.
     */
    public LineString probe(Coordinate a, Coordinate b) {
        return trimmed(a, b);
    }

    /**
     * Первое препятствие, через которое проходит отрезок, или {@code null}.
     * Возвращается сам объект, а не флаг: диагностика должна называть, что именно
     * помешало, иначе «маршрут не найден» невозможно объяснить.
     */
    public Obstacle blockingObstacle(Coordinate a, Coordinate b, int dn, String exemptOksId) {
        return blockingObstacle(trimmed(a, b), dn, exemptOksId);
    }

    @SuppressWarnings("unchecked")
    public Obstacle blockingObstacle(LineString segment, int dn, String exemptOksId) {
        if (segment == null) {
            return null;
        }
        Layer layer = layer(dn);
        List<Obstacle> candidates = layer.forbiddenIndex.query(segment.getEnvelopeInternal());
        for (Obstacle o : candidates) {
            if (exemptOksId != null && exemptOksId.equals(o.getOwnerOksId())) {
                continue;
            }
            if (!o.getPreparedBuffered().intersects(segment)) {
                continue;
            }
            // Быстрый фильтр сработал — проверяем точно: касание границы допустимо,
            // заход внутрь запретной зоны — нет. Шаблон DE-9IM "T********" означает
            // пересечение внутренностей.
            if (o.getBuffered().relate(segment, "T********")) {
                return o;
            }
        }
        return null;
    }

    /**
     * Множитель стоимости отрезка за специальные проходы.
     * Раздел 8.1 ТП: стоимость специального участка умножается на Kспец, поэтому
     * при поиске пути отрезок, пересекающий такой объект, взвешивается тем же
     * коэффициентом — приведённая длина остаётся сопоставимой с обычной.
     */
    public double crossingCostFactor(Coordinate a, Coordinate b, int dn) {
        return crossingCostFactor(trimmed(a, b), dn);
    }

    @SuppressWarnings("unchecked")
    public double crossingCostFactor(LineString segment, int dn) {
        if (segment == null) {
            return 1.0;
        }
        double total = segment.getLength();
        if (total <= 0) {
            return 1.0;
        }
        Layer layer = layer(dn);
        List<Obstacle> candidates = layer.specialIndex.query(segment.getEnvelopeInternal());
        double weighted = total;
        for (Obstacle o : candidates) {
            if (!o.getPreparedSource().intersects(segment)) {
                continue;
            }
            Geometry inside = segment.intersection(o.getSource());
            double crossed = inside.getLength();
            if (crossed <= 0) {
                continue;
            }
            // Специальный участок шире самого объекта: по specialMarginM с каждой стороны
            // от его границы либо от точки пересечения (таблица 5.1).
            double special = Math.min(total, crossed + 2 * o.getRule().getSpecialMarginM());
            weighted += special * (o.getRule().getKSpecial() - 1.0);
        }
        return weighted / total;
    }

    /**
     * Соблюдён ли минимальный угол пересечения для всех специальных объектов на отрезке
     * (таблица 5.1: дороги и трамвайные пути — не менее 45°).
     */
    public boolean crossingAngleOk(Coordinate a, Coordinate b, int dn) {
        return crossingAngleOk(trimmed(a, b), dn);
    }

    @SuppressWarnings("unchecked")
    public boolean crossingAngleOk(LineString segment, int dn) {
        if (segment == null) {
            return true;
        }
        Layer layer = layer(dn);
        List<Obstacle> candidates = layer.specialIndex.query(segment.getEnvelopeInternal());
        for (Obstacle o : candidates) {
            Double minAngle = o.getRule().getMinCrossingAngleDeg();
            if (minAngle == null) {
                continue;
            }
            if (!o.getPreparedSource().intersects(segment)) {
                continue;
            }
            Geometry boundary = o.getSource().getBoundary();
            if (!minAngleSatisfied(segment, boundary, minAngle)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Проверка угла между трассой и границей объекта в местах их пересечения.
     * Берётся наименьший острый угол по всем точкам пересечения: нарушение хотя бы
     * в одной делает отрезок недопустимым.
     */
    private boolean minAngleSatisfied(LineString segment, Geometry boundary, double minAngleDeg) {
        Geometry crossings = boundary.intersection(segment);
        if (crossings.isEmpty()) {
            return true;
        }
        Coordinate s0 = segment.getCoordinateN(0);
        Coordinate s1 = segment.getCoordinateN(segment.getNumPoints() - 1);

        for (Coordinate x : crossings.getCoordinates()) {
            Coordinate[] edge = nearestBoundaryEdge(boundary, x);
            if (edge == null) {
                continue;
            }
            double angle = Geo.acuteAngleDeg(s0, s1, edge[0], edge[1]);
            if (angle < minAngleDeg) {
                return false;
            }
        }
        return true;
    }

    /** Ребро контура, ближайшее к точке пересечения. */
    private Coordinate[] nearestBoundaryEdge(Geometry boundary, Coordinate point) {
        Coordinate[] best = null;
        double bestDist = Double.MAX_VALUE;
        for (int g = 0; g < boundary.getNumGeometries(); g++) {
            Geometry part = boundary.getGeometryN(g);
            Coordinate[] cs = part.getCoordinates();
            for (int i = 0; i + 1 < cs.length; i++) {
                double d = org.locationtech.jts.algorithm.Distance
                        .pointToSegment(point, cs[i], cs[i + 1]);
                if (d < bestDist) {
                    bestDist = d;
                    best = new Coordinate[]{cs[i], cs[i + 1]};
                }
            }
        }
        return best;
    }

    // =================================================================================
    //  Вершины для графа видимости
    // =================================================================================

    /**
     * Вершины буферизованных контуров — опорные точки графа видимости.
     * Берутся только выпуклые вершины внешних колец: кратчайший путь огибает препятствие
     * именно по ним, вогнутые вершины и вершины дыр в обход не попадают.
     */
    public List<Coordinate> visibilityVertices(int dn) {
        Layer layer = layer(dn);
        List<Coordinate> out = new ArrayList<>();
        Map<Long, Coordinate> unique = new LinkedHashMap<>();

        for (Obstacle o : layer.forbidden) {
            collectConvexVertices(o.getBuffered(), unique);
        }
        for (Obstacle o : layer.special) {
            // Для специальных объектов огибание тоже возможно: обойти дорогу дешевле,
            // чем пересечь её с коэффициентом 1,60, если обход короткий.
            collectConvexVertices(o.getBuffered(), unique);
        }
        out.addAll(unique.values());
        return out;
    }

    private void collectConvexVertices(Geometry geometry, Map<Long, Coordinate> out) {
        for (int g = 0; g < geometry.getNumGeometries(); g++) {
            Geometry part = geometry.getGeometryN(g);
            if (!(part instanceof Polygon)) {
                continue;
            }
            Coordinate[] ring = ((Polygon) part).getExteriorRing().getCoordinates();
            int n = ring.length - 1;   // последняя точка повторяет первую
            if (n < 3) {
                continue;
            }
            // Внешнее кольцо JTS ориентировано по часовой стрелке; выпуклая вершина —
            // та, где обход поворачивает в ту же сторону, что и всё кольцо.
            boolean clockwise = org.locationtech.jts.algorithm.Orientation.isCCW(ring);
            for (int i = 0; i < n; i++) {
                Coordinate prev = ring[(i - 1 + n) % n];
                Coordinate cur = ring[i];
                Coordinate next = ring[(i + 1) % n];
                int turn = org.locationtech.jts.algorithm.Orientation.index(prev, cur, next);
                boolean convex = clockwise
                        ? turn == org.locationtech.jts.algorithm.Orientation.CLOCKWISE
                        : turn == org.locationtech.jts.algorithm.Orientation.COUNTERCLOCKWISE;
                if (convex) {
                    out.putIfAbsent(key(cur), cur);
                }
            }
        }
    }

    private static long key(Coordinate c) {
        // Ключ с точностью до миллиметра — совпадающие вершины соседних буферов
        // не должны порождать дубликаты узлов графа.
        return Objects.hash(Math.round(c.x * 1000), Math.round(c.y * 1000));
    }

    /** Отрезок, укороченный с обоих концов: концы лежат на границах препятствий. */
    private LineString trimmed(Coordinate a, Coordinate b) {
        double eps = routingProps.getEdgeEndEpsilon();
        double len = a.distance(b);
        if (len <= 2 * eps) {
            return null;
        }
        double t = eps / len;
        Coordinate a1 = new Coordinate(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
        Coordinate b1 = new Coordinate(b.x - (b.x - a.x) * t, b.y - (b.y - a.y) * t);
        // Без модели точности: округление до миллиметра здесь ничего не даёт,
        // а на сотнях тысяч проверок заметно в профиле.
        return RAW.createLineString(new Coordinate[]{a1, b1});
    }

    /** Все запрещающие препятствия слоя — нужны для отчётов и отрисовки. */
    public List<Obstacle> forbidden(int dn) {
        return layer(dn).forbidden;
    }

    /** Все объекты со специальным проходом. */
    public List<Obstacle> special(int dn) {
        return layer(dn).special;
    }
}
