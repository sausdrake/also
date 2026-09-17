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
     *
     * MEDIA-HARDENING-1: this same atomic UPDATE also clears
     * pendingThumbnailStorageKey to NULL - the promotion from "pending" to
     * "finalized" is one database write, not two, so a successfully
     * finalized row can never be selected by findPendingThumbnailOrphans().
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

    // ==================== MEDIA-HARDENING-1: recoverable thumbnail orphans ====================

    /**
     * Atomically records the R2 key a processing attempt is about to upload
     * a thumbnail to, BEFORE that upload happens - the durable write-ahead
     * marker that lets a crashed attempt (no exception, no compensating
     * delete) still be discovered after stale-PROCESSING recovery. Guarded
     * only by processingStatus='PROCESSING' - deliberately NOT also guarded
     * by "pending key currently null", so a fresh legitimate attempt is
     * never blocked from declaring its own key by a stale marker a prior
     * crashed attempt left behind (that marker is cleaned up independently
     * by reconcilePendingThumbnailOrphans()).
     */
    boolean declarePendingThumbnail(Long id, String thumbnailStorageKey);

    /**
     * Atomically clears pendingThumbnailStorageKey - but ONLY if it still
     * equals expectedKey. This expected-key guard is the sole safeguard
     * against an older reconciliation/cleanup attempt clearing a NEWER
     * processing attempt's marker after a retry has already overwritten it
     * with a fresh key (declarePendingThumbnail() has no uniqueness
     * constraint, so the column always reflects only the most recent
     * attempt's key).
     */
    boolean clearPendingThumbnail(Long id, String expectedKey);

    /**
     * Bounded batch of rows with a non-null pendingThumbnailStorageKey whose
     * processingStatus is no longer PROCESSING - i.e. processing moved on
     * (finished, failed, or was stale-reset) without ever clearing the
     * marker itself, meaning the R2 object it names was never referenced by
     * a successful finalization and is a genuine orphan candidate. Cannot
     * ever match a successfully finalized row: finalizeProcessingReady()
     * clears this column in the SAME atomic UPDATE that sets
     * thumbnailStorageKey. Mirrors findStaleProcessing()'s bounded-query
     * pattern exactly.
     */
    List<DiagnosticMedia> findPendingThumbnailOrphans(int limit);

    // ==================== mandatory-video completion gate ====================

    /**
     * Existence check used by DiagnosticRequestService's completion guard: does at
     * least one row for this exact (diagnosticRequestId, inspectionItemId) pair have
     * mediaType=VIDEO, uploadStatus=READY, and processingStatus=READY. Deliberately
     * an EXISTS check, not a count=1 check - multiple qualifying rows are tolerated
     * by design (the locked mandatory-video business invariant).
     */
    boolean existsQualifyingVideo(Long diagnosticRequestId, Long inspectionItemId);
}
