package ru.lct.heatroute.ingest;

import org.locationtech.jts.geom.Geometry;

import java.util.Map;
import java.util.Optional;

/**
 * Объект GeoJSON сразу после разбора: атрибуты как есть, геометрия уже в рабочей проекции.
 * <p>
 * Доступ к атрибутам идёт через методы с приведением типов, потому что во входных
 * данных одно и то же поле приходит то числом, то строкой: конкурсный набор 2026 года
 * прислал целочисленные {@code id} там, где техническое приложение объявляет строку.
 */
public class RawFeature {

    private final Map<String, Object> properties;
    private final Geometry geometry;

    public RawFeature(Map<String, Object> properties, Geometry geometry) {
        this.properties = properties;
        this.geometry = geometry;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    public Geometry geometry() {
        return geometry;
    }

    public boolean has(String key) {
        return properties.get(key) != null;
    }

    /** Значение как строка независимо от того, пришло оно числом или строкой. */
    public String str(String key) {
        Object v = properties.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Double) {
            double d = (Double) v;
            // Целое, приехавшее как 12.0, не должно превратиться в идентификатор "12.0".
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
        }
        return String.valueOf(v);
    }

    public Optional<Double> num(String key) {
        Object v = properties.get(key);
        if (v instanceof Number) {
            return Optional.of(((Number) v).doubleValue());
        }
        if (v instanceof String) {
            try {
                return Optional.of(Double.parseDouble(((String) v).trim().replace(',', '.')));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public Optional<Integer> intVal(String key) {
        return num(key).map(d -> (int) Math.round(d));
    }
}
