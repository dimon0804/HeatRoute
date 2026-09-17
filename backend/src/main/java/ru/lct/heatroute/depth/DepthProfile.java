package ru.lct.heatroute.depth;

import lombok.Value;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Профиль глубины вдоль одного участка новой сети.
 * <p>
 * Хранится как набор опорных точек «расстояние от начала участка — глубина до верха
 * расчётного габарита». Между опорными точками глубина меняется линейно: раздел 4
 * приложения по глубине требует прямого наклонного участка, а не кривой.
 */
@Value
public class DepthProfile {

    /** Опорная точка профиля. */
    @Value
    public static class Point {
        /** Расстояние от начала участка, м. */
        double station;
        /** Глубина до верха расчётного габарита, м. */
        double depth;
    }

    List<Point> points;

    public static DepthProfile flat(double length, double depth) {
        return new DepthProfile(List.of(new Point(0, depth), new Point(length, depth)));
    }

    /** Глубина в точке на расстоянии {@code station} от начала участка. */
    public double depthAt(double station) {
        if (points.isEmpty()) {
            return 0;
        }
        if (station <= points.get(0).getStation()) {
            return points.get(0).getDepth();
        }
        for (int i = 0; i + 1 < points.size(); i++) {
            Point a = points.get(i);
            Point b = points.get(i + 1);
            if (station <= b.getStation()) {
                double span = b.getStation() - a.getStation();
                if (span <= 1e-9) {
                    return b.getDepth();
                }
                double t = (station - a.getStation()) / span;
                return a.getDepth() + (b.getDepth() - a.getDepth()) * t;
            }
        }
        return points.get(points.size() - 1).getDepth();
    }

    /** Наибольшая глубина профиля — по ней видно, насколько тяжёлый участок. */
    public double maxDepth() {
        return points.stream().mapToDouble(Point::getDepth).max().orElse(0);
    }

    /** Точки, в которых меняется уклон: именно там участок делится на части. */
    public List<Double> breakStations() {
        List<Double> out = new ArrayList<>();
        for (int i = 1; i + 1 < points.size(); i++) {
            double before = slope(points.get(i - 1), points.get(i));
            double after = slope(points.get(i), points.get(i + 1));
            if (Math.abs(before - after) > 1e-9) {
                out.add(points.get(i).getStation());
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static double slope(Point a, Point b) {
        double span = b.getStation() - a.getStation();
        return span <= 1e-9 ? 0 : (b.getDepth() - a.getDepth()) / span;
    }
}
