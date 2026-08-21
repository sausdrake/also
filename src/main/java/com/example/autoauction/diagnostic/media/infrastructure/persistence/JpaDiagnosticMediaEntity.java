package com.example.autoauction.diagnostic.media.infrastructure.persistence;

import com.example.autoauction.diagnostic.media.domain.MediaType;
import com.example.autoauction.diagnostic.media.domain.ProcessingStatus;
import com.example.autoauction.diagnostic.media.domain.UploadStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Entity
@Table(name = "diagnostic_media")
@Data
public class JpaDiagnosticMediaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "diagnostic_request_id", nullable = false)
    private Long diagnosticRequestId;

    @Column(name = "inspection_item_id", nullable = false)
    private Long inspectionItemId;

    @Column(name = "uploader_id", nullable = false)
    private Long uploaderId;

    @Column(name = "storage_key", nullable = false, unique = true, length = 500)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 20)
    private MediaType mediaType;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "caption", length = 1000)
    private String caption;

    @Enumerated(EnumType.STRING)
    @Column(name = "upload_status", nullable = false, length = 20)
    private UploadStatus uploadStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 20)
    private ProcessingStatus processingStatus;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "thumbnail_storage_key", length = 500)
    private String thumbnailStorageKey;

    /**
     * MEDIA-HARDENING-1: R2 key of a thumbnail uploaded but not yet promoted
     * into thumbnailStorageKey by a successful finalizeProcessingReady()
     * commit. Written only by the declarePendingThumbnail() atomic UPDATE,
     * cleared only by finalizeProcessingReady() (atomically, on success),
     * clearPendingThumbnail() (compensating delete / orphan reconciliation),
     * or the deleted-media cleanup sweep. Never touched by ordinary entity
     * saves - same discipline as processingClaimedAt below.
     */
    @Column(name = "pending_thumbnail_storage_key", length = 500)
    private String pendingThumbnailStorageKey;

    /**
     * MEDIA-3.4: set ONLY by the atomic PENDING -> PROCESSING claim
     * transition (see SpringDataDiagnosticMediaJpaRepository.claimForProcessing).
     * Deliberately NOT touched by onCreate()/onUpdate() below - ordinary
     * entity saves (e.g. DiagnosticMediaService.updateCaption/reorder) must
     * never overwrite or fabricate a claim timestamp.
     */
    @Column(name = "processing_claimed_at")
    private OffsetDateTime processingClaimedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (sortOrder == null) sortOrder = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
