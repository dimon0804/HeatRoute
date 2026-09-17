package ru.lct.heatroute.persistence;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Запуск расчёта вариантов по одному набору. */
@Entity
@Table(name = "calculation_job")
@Getter
@Setter
@NoArgsConstructor
public class CalculationJobEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_id", nullable = false)
    private DatasetEntity dataset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private JobStatus status;

    /** Доля выполненного, 0..1. Расчёт длится десятки секунд, интерфейсу нужен прогресс. */
    @Column(nullable = false)
    private double progress;

    /** Человекочитаемое название текущего этапа. */
    @Column(length = 256)
    private String stage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "params_json", columnDefinition = "text")
    private String paramsJson;

    @Column(name = "stats_json", columnDefinition = "text")
    private String statsJson;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
}
