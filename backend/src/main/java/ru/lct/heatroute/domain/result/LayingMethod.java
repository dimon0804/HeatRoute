package ru.lct.heatroute.domain.result;

/** Способ прокладки участка новой сети (раздел 5 ТП). */
public enum LayingMethod {

    /** Обычная подземная бесканальная прокладка. */
    BASE("base"),

    /** Специальный проход через объект, допускающий пересечение. */
    SPECIAL("special");

    private final String code;

    LayingMethod(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
