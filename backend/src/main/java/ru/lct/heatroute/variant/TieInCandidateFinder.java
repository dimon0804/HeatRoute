package ru.lct.heatroute.variant;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Component;
import ru.lct.heatroute.domain.model.ExistingChamber;
import ru.lct.heatroute.domain.model.ExistingSegment;
import ru.lct.heatroute.domain.model.FutureOks;
import ru.lct.heatroute.domain.model.InputScene;
import ru.lct.heatroute.domain.reference.ReferenceCatalog;
import ru.lct.heatroute.geo.Geo;
import ru.lct.heatroute.routing.RoutingProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Набор кандидатов на точку врезки.
 * <p>
 * Точка врезки — самое дорогое решение в задаче: она задаёт и длину новой трассы,
 * и объём реконструкции существующей сети до источника. Полный перебор положений
 * бессмысленен, случайная выборка ненадёжна, поэтому кандидаты формируются
 * по трём осмысленным основаниям:
 * <ol>
 *   <li>существующие тепловые камеры — врезка в них не требует строительства новой;</li>
 *   <li>проекции точек подключения ОКС на сеть — ближайший выход для одного объекта;</li>
 *   <li>равномерная дискретизация сети — места, выгодные для группы ОКС,
 *       а не для одного.</li>
 * </ol>
 * К каждому кандидату сразу применяется правило 10 м раздела 8.2, чтобы сравнение
 * шло с уже учтённой разницей «врезка в камеру» против «врезка плюс новая камера».
 */
@Slf4j
@Component
public class TieInCandidateFinder {

    private final ReferenceCatalog catalog;
    private final RoutingProperties props;

    public TieInCandidateFinder(ReferenceCatalog catalog, RoutingProperties props) {
        this.catalog = catalog;
        this.props = props;
    }

    public List<TieInCandidate> find(InputScene scene) {
        Map<Long, TieInCandidate> unique = new LinkedHashMap<>();
        double snapRadius = catalog.props().getChamberSnapRadius();
        int maxDegree = catalog.props().getMaxChamberDegree();

        // --- 1. Существующие камеры -----------------------------------------------------
        for (ExistingChamber ch : scene.getChambers()) {
            if (ch.getExistingDegree() >= maxDegree) {
                // К камере уже примыкает предельное число участков: добавить ещё один
                // нельзя, и кандидатом она быть не может (раздел 3 ТП).
                continue;
            }
            Coordinate c = ch.getLocation().getCoordinate();
            unique.put(key(c), TieInCandidate.builder()
                    .id("tiec:" + ch.getId())
                    .location(c)
                    .existingObjectId(ch.getId())
                    .existingObjectType("heat_chamber")
                    .existingDiameter(ch.getDiameter())
                    .positionFraction(0)
                    .usesExistingChamber(true)
                    .existingChamberId(ch.getId())
                    .chamberExistingDegree(ch.getExistingDegree())
                    .graphNodeIndex(-1)
                    .build());
        }

        // --- 2. Проекции точек подключения ----------------------------------------------
        for (FutureOks oks : scene.getFutureOks()) {
            Coordinate p = oks.getConnectionPoint().getCoordinate();
            ExistingSegment nearest = null;
            double bestDist = Double.MAX_VALUE;
            for (ExistingSegment seg : scene.getSegments()) {
                double d = Geo.distance(seg.getGeometry(), p);
                if (d < bestDist) {
                    bestDist = d;
                    nearest = seg;
                }
            }
            if (nearest == null || bestDist > props.getTieInSearchRadius()) {
                continue;
            }
            Coordinate projected = Geo.nearestOn(nearest.getGeometry(), p);
            addSegmentCandidate(unique, scene, nearest, projected, snapRadius, maxDegree);
        }

        // --- 3. Равномерная дискретизация сети ------------------------------------------
        double step = props.getTieInSampleStep();
        for (ExistingSegment seg : scene.getSegments()) {
            LineString line = seg.getGeometry();
            double total = line.getLength();
            if (total <= 0) {
                continue;
            }
            LengthIndexedLine indexed = new LengthIndexedLine(line);
            for (double s = 0; s <= total; s += step) {
                addSegmentCandidate(unique, scene, seg, indexed.extractPoint(s),
                        snapRadius, maxDegree);
            }
            addSegmentCandidate(unique, scene, seg, indexed.extractPoint(total),
                    snapRadius, maxDegree);
        }

        List<TieInCandidate> result = new ArrayList<>(unique.values());
        log.debug("Кандидатов врезки: {} (из них в существующих камерах {})",
                result.size(), result.stream().filter(TieInCandidate::isUsesExistingChamber).count());
        return result;
    }

    /**
     * Добавляет кандидата на существующем участке, применяя правило 10 м раздела 8.2:
     * если рядом есть существующая камера с запасом по числу примыканий, врезка
     * выполняется в неё, и отдельный кандидат на участке не нужен.
     */
    private void addSegmentCandidate(Map<Long, TieInCandidate> unique,
                                     InputScene scene,
                                     ExistingSegment segment,
                                     Coordinate location,
                                     double snapRadius,
                                     int maxDegree) {
        ExistingChamber near = null;
        double bestDist = Double.MAX_VALUE;
        for (ExistingChamber ch : scene.getChambers()) {
            double d = ch.getLocation().getCoordinate().distance(location);
            if (d <= snapRadius && d < bestDist) {
                bestDist = d;
                near = ch;
            }
        }
        if (near != null && near.getExistingDegree() < maxDegree) {
            // Кандидат «притягивается» к камере: сама камера уже добавлена на шаге 1.
            return;
        }

        long k = key(location);
        if (unique.containsKey(k)) {
            return;
        }
        unique.put(k, TieInCandidate.builder()
                .id("tien:" + segment.getId() + ":" + k)
                .location(location)
                .existingObjectId(segment.getId())
                .existingObjectType("heat_network")
                .existingDiameter(segment.getDiameter())
                .positionFraction(Geo.projectFraction(segment.getGeometry(), location))
                .usesExistingChamber(false)
                .existingChamberId(null)
                .chamberExistingDegree(0)
                .graphNodeIndex(-1)
                .build());
    }

    /** Ключ с точностью до дециметра: кандидаты ближе этого неразличимы по смыслу. */
    private static long key(Coordinate c) {
        long x = Math.round(c.x * 10);
        long y = Math.round(c.y * 10);
        return x * 1_000_000_007L + y;
    }
}
