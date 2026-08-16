package com.example.autoauction.diagnostic.media.domain;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * MEDIA-3.2. Canonical attachment is (diagnosticRequestId, inspectionItemId) -
 * NOT InspectionAnswer (MEDIA-2 decision: InspectionAnswer rows are deleted
 * and reinserted wholesale on every saveInspectionAnswers() call, so an FK
 * from there would orphan media as a side effect). No templateId column: the
 * request's template is already authoritative via
 * DiagnosticRequest.templateId (MEDIA-3.1A) and is verified through the
 * inspectionItemId -> group -> template chain at write time, not duplicated
 * here.
 */
@Data
public class DiagnosticMedia {
    private Long id;
    private Long diagnosticRequestId;
    private Long inspectionItemId;
    private Long uploaderId;

    private String storageKey;

    private MediaType mediaType;
    private String mimeType;
    private Long sizeBytes;

    private Integer sortOrder;
    private String caption;

    private UploadStatus uploadStatus;
    private ProcessingStatus processingStatus;

    private Integer durationSeconds;
    private Integer width;
    private Integer height;
    private String thumbnailStorageKey;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
