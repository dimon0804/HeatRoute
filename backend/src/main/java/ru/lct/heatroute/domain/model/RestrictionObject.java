package ru.lct.heatroute.domain.model;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Geometry;
import ru.lct.heatroute.domain.reference.ReferenceProperties.RestrictionRow;

/** Пространственное ограничение с уже разрешённым правилом учёта (таблица 5.1 ТП). */
@Value
@Builder(toBuilder = true)
public class RestrictionObject {

    String id;

    /** Геометрия в рабочей проекции: полигон, линия или точка — в соответствии с типом. */
    Geometry geometry;

    /** Значение {@code restriction_type} как оно пришло во входных данных. */
    String rawType;

    /** Тип после разрешения псевдонима — ключ таблицы 5.1. */
    String canonicalType;

    /** Правило учёта из справочника. */
    RestrictionRow rule;

    /** {@code true}, если тип не найден в справочнике и применено правило по умолчанию. */
    boolean unknownType;

    /** Условный диаметр, если ограничение — существующая тепловая сеть (для габарита). */
    Integer diameter;

    /** Адрес или иное описание из входных данных; используется только в отчётах. */
    String address;

    /**
     * ID перспективных ОКС, чьим собственным контуром опознан этот объект.
     * Пустое множество — обычное препятствие. Трассы этих ОКС проходят внутрь контура
     * к своим ИТП без соблюдения клиренса; все прочие трассы обходят объект как обычно.
     * <p>
     * Владельцев именно несколько, а не один: в конкурсном наборе есть здание
     * с двумя точками подключения, и если запомнить только последнюю, собственный
     * контур перекроет подход к первой.
     */
    @lombok.Builder.Default
    java.util.Set<String> ownerOksIds = java.util.Set.of();

    /** Является ли объект собственным контуром указанного ОКС. */
    public boolean isOwnedBy(String oksId) {
        return oksId != null && ownerOksIds.contains(oksId);
    }
}
