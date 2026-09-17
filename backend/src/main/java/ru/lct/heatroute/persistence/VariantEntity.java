package ru.lct.heatroute.persistence;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import java.util.UUID;

/** Один вариант подключения в составе расчёта. */
@Entity
@Table(name = "variant")
@Getter
@Setter
@NoArgsConstructor
public class VariantEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private CalculationJobEntity job;

    /** Значение variant_id в выгрузке (раздел 10 ТП). */
    @Column(name = "variant_code", nullable = false, length = 64)
    private String variantCode;

    @Column(name = "rank", nullable = false)
    private int rank;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "summary_json", nullable = false, columnDefinition = "text")
    private String summaryJson;

    @Column(columnDefinition = "text")
    private String fingerprint;

    /** Дублируются колонками, чтобы сортировать и сравнивать варианты без разбора JSON. */
    @Column(nullable = false)
    private double score;

    @Column(name = "calculated_cost", nullable = false)
    private double calculatedCost;

    @Column(name = "total_length", nullable = false)
    private double totalLength;
}
