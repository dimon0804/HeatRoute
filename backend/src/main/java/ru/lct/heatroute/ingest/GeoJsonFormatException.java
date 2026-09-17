package ru.lct.heatroute.ingest;

/** Входной файл не является корректным GeoJSON FeatureCollection. */
public class GeoJsonFormatException extends RuntimeException {

    public GeoJsonFormatException(String message) {
        super(message);
    }

    public GeoJsonFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
