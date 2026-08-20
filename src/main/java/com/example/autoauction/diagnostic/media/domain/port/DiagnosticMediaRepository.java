package com.example.autoauction.diagnostic.media.domain.port;

import com.example.autoauction.diagnostic.media.domain.DiagnosticMedia;
import com.example.autoauction.diagnostic.media.domain.UploadStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface DiagnosticMediaRepository {

    DiagnosticMedia save(DiagnosticMedia media);

    Optional<DiagnosticMedia> findById(Long id);

    List<DiagnosticMedia> findByRequestAndItem(Long diagnosticRequestId, Long inspectionItemId);

    List<DiagnosticMedia> findByRequest(Long diagnosticRequestId);

    /** PENDING_UPLOAD rows older than the given cutoff - reconciliation expiry candidates. */
    List<DiagnosticMedia> findPendingUploadOlderThan(OffsetDateTime cutoff);

    /** All rows in a given upload status - used by reconciliation for READY-existence checks and DELETED sweeps. */
    List<DiagnosticMedia> findByUploadStatus(UploadStatus status);

    /**
     * Atomic conditional status transition; returns true only if the row was
     * actually in fromStatus and is now updated to toStatus. Guards against
     * duplicate finalize/expire/promote races the same way
     * DiagnosticRequest.bindTemplate does (MEDIA-3.1A precedent).
     */
    boolean updateUploadStatusIfCurrent(Long id, UploadStatus fromStatus, UploadStatus toStatus);

    /** Atomic PENDING_UPLOAD -> READY, also persisting the server-verified (not client-declared) mimeType/sizeBytes. */
    boolean finalizeReady(Long id, String verifiedMimeType, long verifiedSizeBytes);

    /** Atomic PENDING_UPLOAD -> FAILED. Never overwrites an already-READY row. */
    boolean markFailed(Long id);

    /** Atomic transition to DELETED from any non-DELETED state. Idempotent: a second call safely returns false. */
    boolean softDelete(Long id);

    /** Full replacement of sortOrder for the given media IDs, 0-based in list order. Atomic. */
    void replaceOrdering(List<Long> orderedMediaIds);

    // ==================== MEDIA-3.4: video processing ====================

    /**
     * Atomic PENDING -> PROCESSING claim, also stamping processingClaimedAt.
     * Requires uploadStatus=READY too, so a soft-deleted or not-yet-uploaded
     * row can never be claimed. Returns true only if this call's UPDATE
     * actually matched the row - the sole concurrency guard, no in-memory
     * lock, no SELECT-then-UPDATE.
     */
    boolean claimForProcessing(Long id);

    /**
     * Atomic PROCESSING -> READY, persisting server-derived metadata.
     * Requires uploadStatus=READY - if the media was soft-deleted while a
     * worker was processing it, this returns false and the caller must not
     * publish the result (see VideoProcessingService, MEDIA-3.4 Phase 6).
     */
    boolean finalizeProcessingReady(Long id, Integer durationSeconds, Integer width, Integer height, String thumbnailStorageKey);

    /** Atomic PROCESSING -> FAILED. Never overwrites a row that already left PROCESSING. */
    boolean markProcessingFailed(Long id);

    /** Atomic PROCESSING -> PENDING, used only by the stale-claim reconciliation sweep. */
    boolean resetStaleProcessing(Long id);

    /**
     * Atomic FAILED -> PENDING, admin-triggered only - never called
     * automatically. Requires uploadStatus=READY, so a soft-deleted FAILED
     * row can never be retried back into the queue.
     */
    boolean retryFailedProcessing(Long id);

    /** Bounded batch of uploadStatus=READY, processingStatus=PENDING candidates, oldest first. */
    List<DiagnosticMedia> findPendingProcessing(int limit);

    /**
     * Bounded batch of processingStatus=PROCESSING rows claimed before the
     * given cutoff - stale-claim candidates, oldest-claimed first. Mirrors
     * findPendingProcessing(int)'s bounded-query pattern exactly (MEDIA-3.4
     * Phase 7: the original Phase 2 signature had no limit - corrected here
     * to the same Pageable-backed shape already established one method
     * above, not a new pattern).
     */
    List<DiagnosticMedia> findStaleProcessing(OffsetDateTime cutoff, int limit);
}
