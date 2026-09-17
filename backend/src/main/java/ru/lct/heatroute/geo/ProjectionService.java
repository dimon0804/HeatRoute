package ru.lct.heatroute.geo;

import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.util.GeometryEditor;
import org.locationtech.proj4j.BasicCoordinateTransform;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.ProjCoordinate;
import org.springframework.stereotype.Service;

/**
 * Преобразование между WGS 84 (EPSG:4326) и рабочей метрической проекцией.
 * <p>
 * Техническое приложение фиксирует EPSG:32637 (UTM 37N): весь расчёт длин, расстояний
 * и буферов ведётся в метрах, вход и выход — в градусах. Зона задаётся параметром,
 * а не константой в коде: набор для проверки может оказаться за пределами 37-й зоны,
 * и тогда достаточно поменять {@code heatroute.geo.utm-zone}, не трогая алгоритм.
 * <p>
 * Класс потокобезопасен на чтение справочников, но {@link CoordinateTransform} у proj4j
 * хранит промежуточное состояние, поэтому объекты преобразования создаются на вызов
 * либо берутся из {@link ThreadLocal}.
 */
@Slf4j
@Service
public class ProjectionService {

    private static final CRSFactory CRS_FACTORY = new CRSFactory();

    private final CoordinateReferenceSystem wgs84;
    private final CoordinateReferenceSystem projected;
    private final int utmZone;
    private final boolean northern;

    private final ThreadLocal<CoordinateTransform> toProjected;
    private final ThreadLocal<CoordinateTransform> toGeographic;

    public ProjectionService(GeoProperties props) {
        this.utmZone = props.getUtmZone();
        this.northern = props.isNorthernHemisphere();
        this.wgs84 = CRS_FACTORY.createFromParameters("WGS84",
                "+proj=longlat +datum=WGS84 +no_defs");
        this.projected = CRS_FACTORY.createFromParameters("UTM" + utmZone,
                String.format("+proj=utm +zone=%d %s+datum=WGS84 +units=m +no_defs",
                        utmZone, northern ? "" : "+south "));
        this.toProjected = ThreadLocal.withInitial(
                () -> new BasicCoordinateTransform(wgs84, projected));
        this.toGeographic = ThreadLocal.withInitial(
                () -> new BasicCoordinateTransform(projected, wgs84));
        log.info("Рабочая проекция: EPSG:{} (UTM зона {}{})",
                epsgCode(), utmZone, northern ? "N" : "S");
    }

    /** Код EPSG рабочей проекции: 326xx для северного полушария, 327xx для южного. */
    public int epsgCode() {
        return (northern ? 32600 : 32700) + utmZone;
    }

    /**
     * Зона UTM, в которую попадает долгота. Используется для проверки, что параметр
     * сервиса согласован с фактическим положением набора.
     */
    public static int utmZoneForLongitude(double lon) {
        return (int) Math.floor((lon + 180.0) / 6.0) + 1;
    }

    /** Градусы → метры рабочей проекции. */
    public Coordinate project(double lon, double lat) {
        ProjCoordinate out = new ProjCoordinate();
        toProjected.get().transform(new ProjCoordinate(lon, lat), out);
        return new Coordinate(out.x, out.y);
    }

    /** Метры рабочей проекции → градусы. */
    public Coordinate unproject(double x, double y) {
        ProjCoordinate out = new ProjCoordinate();
        toGeographic.get().transform(new ProjCoordinate(x, y), out);
        return new Coordinate(out.x, out.y);
    }

    /** Геометрия целиком из градусов в метры; Z-координаты сохраняются без изменений. */
    public Geometry project(Geometry geographic) {
        return transform(geographic, true);
    }

    /** Геометрия целиком из метров в градусы; Z-координаты сохраняются без изменений. */
    public Geometry unproject(Geometry projectedGeometry) {
        return transform(projectedGeometry, false);
    }

    private Geometry transform(Geometry geometry, boolean forward) {
        GeometryEditor editor = new GeometryEditor(geometry.getFactory());
        return editor.edit(geometry, new GeometryEditor.CoordinateSequenceOperation() {
            @Override
            public CoordinateSequence edit(CoordinateSequence seq, Geometry geom) {
                CoordinateSequence copy = seq.copy();
                ProjCoordinate in = new ProjCoordinate();
                ProjCoordinate out = new ProjCoordinate();
                CoordinateTransform tr = forward ? toProjected.get() : toGeographic.get();
                for (int i = 0; i < copy.size(); i++) {
                    in.x = copy.getX(i);
                    in.y = copy.getY(i);
                    tr.transform(in, out);
                    copy.setOrdinate(i, CoordinateSequence.X, out.x);
                    copy.setOrdinate(i, CoordinateSequence.Y, out.y);
                }
                return copy;
            }
        });
    }
}
