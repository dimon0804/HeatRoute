package ru.lct.heatroute.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Repository
public interface VariantFeatureRepository extends JpaRepository<VariantFeatureEntity, Long> {

    List<VariantFeatureEntity> findByVariantIdOrderByOrdinalAsc(UUID variantId);

    List<VariantFeatureEntity> findByVariantIdAndObjectTypeOrderByOrdinalAsc(
            UUID variantId, String objectType);

    /**
     * Потоковая выдача объектов всех вариантов расчёта в порядке выгрузки.
     * Используется при формировании выходного файла: строки уходят в поток ответа
     * по одной, документ целиком в памяти не собирается (требование ТЗ о 500 МБ).
     */
    @Query("select f from VariantFeatureEntity f "
            + "where f.variant.job.id = :jobId "
            + "order by f.variant.rank asc, f.ordinal asc")
    Stream<VariantFeatureEntity> streamByJob(@Param("jobId") UUID jobId);

    long countByVariantId(UUID variantId);
}
