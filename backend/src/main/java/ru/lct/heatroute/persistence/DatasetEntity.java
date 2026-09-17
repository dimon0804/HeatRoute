package ru.lct.heatroute.persistence;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Загруженный входной набор GeoJSON. */
@Entity
@Table(name = "dataset")
@Getter
@Setter
@NoArgsConstructor
public class DatasetEntity {

    @Id
    private UUID id;

    @Column(name = "original_name", nullable = false, length = 512)
    private String originalName;

    /** Путь во временном дисковом хранилище; в базу файл не кладётся. */
    @Column(name = "stored_path", nullable = false, length = 1024)
    private String storedPath;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "feature_count", nullable = false)
    private long featureCount;

    @Column(name = "uploaded_at", nullable = false)
    private OffsetDateTime uploadedAt;

    @Column(name = "summary_json", columnDefinition = "text")
    private String summaryJson;

    @Column(name = "diagnostics_json", columnDefinition = "text")
    private String diagnosticsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DatasetStatus status;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
}
