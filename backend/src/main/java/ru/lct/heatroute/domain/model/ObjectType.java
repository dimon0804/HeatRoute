package ru.lct.heatroute.domain.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Значения атрибута {@code object_type} из таблиц 2.1 и 10 технического приложения.
 * Входные и выходные типы держатся в одном перечислении: выходной GeoJSON читается
 * тем же парсером, что и входной, — это нужно для автопроверок и для повторной
 * загрузки ранее выгруженного результата.
 */
public enum ObjectType {

    // --- вход (таблица 2.1) ---
    SOURCE("source", Kind.INPUT),
    HEAT_NETWORK("heat_network", Kind.INPUT),
    HEAT_CHAMBER("heat_chamber", Kind.INPUT),
    OKS_FUTURE("oks_future", Kind.INPUT),
    OKS_CONNECTION_POINT("oks_connection_point", Kind.INPUT),
    OKS_EXISTING("oks_existing", Kind.INPUT),
    RESTRICTION("restriction", Kind.INPUT),

    // --- выход (раздел 10) ---
    TIE_IN("tie_in", Kind.OUTPUT),
    HEAT_NETWORK_RECONSTRUCTION("heat_network_reconstruction", Kind.OUTPUT),
    HEAT_CHAMBER_RECONSTRUCTION("heat_chamber_reconstruction", Kind.OUTPUT),
    TECHNICAL_NODE("technical_node", Kind.OUTPUT),
    VARIANT_SUMMARY("variant_summary", Kind.OUTPUT);

    public enum Kind { INPUT, OUTPUT }

    private final String code;
    private final Kind kind;

    ObjectType(String code, Kind kind) {
        this.code = code;
        this.kind = kind;
    }

    public String code() {
        return code;
    }

    public Kind kind() {
        return kind;
    }

    /** Разбор значения из GeoJSON; регистр и дефисы не важны. */
    public static Optional<ObjectType> of(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String k = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return Arrays.stream(values()).filter(t -> t.code.equals(k)).findFirst();
    }
}
