package ru.lct.heatroute.geo;

import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Регулярная хеш-сетка для поиска ближайшей точки с допуском.
 * <p>
 * {@code STRtree} из JTS требует полной загрузки до первого запроса и не принимает
 * вставки после построения, а сшивание концов участков устроено ровно наоборот:
 * каждая следующая точка ищется среди уже добавленных. Сетка с шагом, равным допуску,
 * решает это за константное время на операцию и не зависит от порядка данных.
 */
public class SpatialHash {

    private final double cellSize;
    private final Map<Long, List<Integer>> cells = new HashMap<>();
    private final List<Coordinate> points = new ArrayList<>();

    public SpatialHash(double cellSize) {
        this.cellSize = Math.max(cellSize, 1e-6);
    }

    /** Добавляет точку и возвращает её индекс. */
    public int add(Coordinate c) {
        int idx = points.size();
        points.add(new Coordinate(c.x, c.y));
        cells.computeIfAbsent(key(c.x, c.y), k -> new ArrayList<>()).add(idx);
        return idx;
    }

    public Coordinate get(int index) {
        return points.get(index);
    }

    public int size() {
        return points.size();
    }

    /**
     * Индекс ближайшей точки в пределах {@code tolerance} или {@code null}.
     * Просматриваются девять ячеек вокруг запроса — этого достаточно, пока
     * допуск не превышает шаг сетки.
     */
    public Integer nearest(Coordinate c, double tolerance) {
        int cx = (int) Math.floor(c.x / cellSize);
        int cy = (int) Math.floor(c.y / cellSize);
        Integer best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                List<Integer> bucket = cells.get(key((cx + dx) * cellSize, (cy + dy) * cellSize));
                if (bucket == null) {
                    continue;
                }
                for (Integer i : bucket) {
                    double d = points.get(i).distance(c);
                    if (d <= tolerance && d < bestDist) {
                        bestDist = d;
                        best = i;
                    }
                }
            }
        }
        return best;
    }

    private long key(double x, double y) {
        long ix = (long) Math.floor(x / cellSize);
        long iy = (long) Math.floor(y / cellSize);
        return ix * 73_856_093L ^ iy * 19_349_663L;
    }
}
