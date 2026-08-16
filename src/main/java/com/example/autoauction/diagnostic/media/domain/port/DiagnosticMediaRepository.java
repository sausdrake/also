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
}
