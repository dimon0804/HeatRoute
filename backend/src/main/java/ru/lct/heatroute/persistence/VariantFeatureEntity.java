package ru.lct.heatroute.persistence;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.locationtech.jts.geom.Geometry;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

/**
 * Объект выходного GeoJSON (раздел 7 ТП), по одному на строку.
 * <p>
 * Геометрия хранится в WGS 84 — ровно в том виде, в каком уходит в выгрузку и на карту,
 * поэтому обратное преобразование выполняется один раз, при сохранении результата.
 */
@Entity
@Table(name = "variant_feature")
@Getter
@Setter
@NoArgsConstructor
public class VariantFeatureEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private VariantEntity variant;

    @Column(name = "object_type", nullable = false, length = 64)
    private String objectType;

    @Column(name = "feature_id", nullable = false, length = 128)
    private String featureId;

    /** Порядок в выгрузке: он воспроизводится при повторной выдаче файла. */
    @Column(nullable = false)
    private int ordinal;

    /**
     * Геометрия в WGS 84; {@code null} у сводной записи варианта — она не является
     * пространственным объектом. Размерность колонкой не ограничена: в режиме расчёта
     * по глубине геометрия несёт Z-координаты, в плоской задаче — нет.
     */
    @Column(columnDefinition = "geometry")
    private Geometry geom;

    @Column(name = "properties_json", nullable = false, columnDefinition = "text")
    private String propertiesJson;
}
