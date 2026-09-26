package ru.lct.heatroute.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.distance.DistanceOp;

import java.util.ArrayList;
import java.util.List;

/**
 * Геометрические примитивы, используемые всеми слоями расчёта.
 * <p>
 * Единая {@link GeometryFactory} на процесс: JTS сравнивает геометрии по SRID
 * и модели точности, и разные фабрики дают неожиданные отказы в пространственных
 * предикатах.
 */
public final class Geo {

    /**
     * Модель точности 1 мм в рабочей проекции. Округление до миллиметра убирает
     * ошибки представления double, из-за которых точка, полученная проекцией на линию,
     * формально не лежит на этой линии.
     */
    public static final GeometryFactory FACTORY =
            new GeometryFactory(new PrecisionModel(1000d));

    private Geo() {
    }

    public static Point point(double x, double y) {
        return FACTORY.createPoint(new Coordinate(x, y));
    }

    public static Point point(Coordinate c) {
        return FACTORY.createPoint(c);
    }

    public static LineString line(List<Coordinate> coords) {
        return FACTORY.createLineString(coords.toArray(new Coordinate[0]));
    }

    public static LineString line(Coordinate a, Coordinate b) {
        return FACTORY.createLineString(new Coordinate[]{a, b});
    }

    /** Ближайшая к {@code p} точка на геометрии {@code g}. */
    public static Coordinate nearestOn(Geometry g, Coordinate p) {
        return DistanceOp.nearestPoints(g, point(p))[0];
    }

    /** Расстояние от точки до геометрии, м. */
    public static double distance(Geometry g, Coordinate p) {
        return g.distance(point(p));
    }

    /**
     * Доля длины линии до ближайшей к {@code p} точки, в пределах {@code [0, 1]}.
     * Нужна, чтобы разделить существующий участок в месте врезки.
     */
    public static double projectFraction(LineString line, Coordinate p) {
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        double index = indexed.project(new Coordinate(p.x, p.y));
        double total = line.getLength();
        return total == 0 ? 0 : Math.max(0, Math.min(1, index / total));
    }

    /**
     * Часть линии между двумя долями длины. Используется для выделения реконструируемой
     * части существующего участка от точки врезки в сторону источника.
     */
    public static LineString substring(LineString line, double fromFraction, double toFraction) {
        double total = line.getLength();
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        double a = Math.max(0, Math.min(1, fromFraction)) * total;
        double b = Math.max(0, Math.min(1, toFraction)) * total;
        Geometry sub = indexed.extractLine(Math.min(a, b), Math.max(a, b));
        return sub instanceof LineString ? (LineString) sub : line;
    }

    /** Координаты геометрии списком (без копии на каждый вызов getCoordinates). */
    public static List<Coordinate> coordinates(LineString line) {
        CoordinateSequence seq = line.getCoordinateSequence();
        List<Coordinate> out = new ArrayList<>(seq.size());
        for (int i = 0; i < seq.size(); i++) {
            out.add(seq.getCoordinate(i));
        }
        return out;
    }

    /**
     * Угол между двумя отрезками в градусах в диапазоне {@code [0, 90]}.
     * Используется при проверке минимального угла пересечения (таблица 2 ТП),
     * где направление пересечения не важно, важен только острый угол.
     */
    public static double acuteAngleDeg(Coordinate a1, Coordinate a2, Coordinate b1, Coordinate b2) {
        double ax = a2.x - a1.x;
        double ay = a2.y - a1.y;
        double bx = b2.x - b1.x;
        double by = b2.y - b1.y;
        double la = Math.hypot(ax, ay);
        double lb = Math.hypot(bx, by);
        if (la == 0 || lb == 0) {
            return 0;
        }
        double cos = Math.abs((ax * bx + ay * by) / (la * lb));
        return Math.toDegrees(Math.acos(Math.min(1, cos)));
    }

    /** Округление до миллиметра — единая точность вывода длин и координат. */
    public static double roundMm(double value) {
        return Math.round(value * 1000d) / 1000d;
    }

    /** Округление до сантиметра — для длин участков в выгрузке. */
    public static double roundCm(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
