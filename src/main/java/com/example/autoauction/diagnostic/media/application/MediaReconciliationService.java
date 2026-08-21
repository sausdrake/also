package com.example.autoauction.diagnostic.media.application;

import com.example.autoauction.diagnostic.media.domain.DiagnosticMedia;
import com.example.autoauction.diagnostic.media.domain.UploadStatus;
import com.example.autoauction.diagnostic.media.domain.port.DiagnosticMediaRepository;
import com.example.autoauction.diagnostic.media.domain.port.StorageClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * MEDIA-3.2, Phase 15. Independent of R-1/R-2/R-3's FinancialRecoveryService
 * and AuctionCompletionScheduler - a separate implementation owning its own
 * work, deliberately not reusing that machinery. Each case is a small,
 * independently-safe operation; none holds a DB transaction across R2 I/O
 * (each DiagnosticMediaRepository call below is its own short, already-
 * transactional operation at the adapter level).
 */
@Slf4j
@Service
public class MediaReconciliationService {

    private final DiagnosticMediaRepository mediaRepository;
    private final StorageClient storageClient;
    private final Duration pendingUploadExpiry;
    private final long maxSizeBytes;

    public MediaReconciliationService(
            DiagnosticMediaRepository mediaRepository,
            StorageClient storageClient,
            @Value("${diagnostic.media.reconciliation.pending-upload-expiry-minutes}") long pendingUploadExpiryMinutes,
            @Value("${diagnostic.media.max-size-bytes}") long maxSizeBytes) {
        this.mediaRepository = mediaRepository;
        this.storageClient = storageClient;
        this.pendingUploadExpiry = Duration.ofMinutes(pendingUploadExpiryMinutes);
        this.maxSizeBytes = maxSizeBytes;
    }

    public void reconcile() {
        reconcilePendingUploads();
        reconcileDeletedObjects();
        checkReadyObjectsExist();
    }

    /**
     * CASE 1: abandoned PENDING_UPLOAD past the expiry threshold -> EXPIRED.
     * CASE 2: DB says PENDING_UPLOAD but the object actually exists and
     * passes validation -> promote to READY (the client's finalize call
     * never arrived, but the upload itself genuinely succeeded).
     */
    private void reconcilePendingUploads() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(pendingUploadExpiry);
        List<DiagnosticMedia> candidates = mediaRepository.findPendingUploadOlderThan(cutoff);

        for (DiagnosticMedia media : candidates) {
            var head = storageClient.headObject(media.getStorageKey());
            if (head.isEmpty()) {
                boolean expired = mediaRepository.updateUploadStatusIfCurrent(
                        media.getId(), UploadStatus.PENDING_UPLOAD, UploadStatus.EXPIRED);
                if (expired) {
                    log.info("MEDIA-3.2 reconciliation: media {} expired (no object, past {} min)",
                            media.getId(), pendingUploadExpiry.toMinutes());
                }
                continue;
            }

            long sizeBytes = head.get().sizeBytes();
            if (sizeBytes <= 0 || sizeBytes > maxSizeBytes) {
                mediaRepository.markFailed(media.getId());
                log.warn("MEDIA-3.2 reconciliation: media {} failed - object size {} invalid", media.getId(), sizeBytes);
                continue;
            }

            byte[] leadingBytes = storageClient.fetchLeadingBytes(media.getStorageKey(), MediaContentValidator.PROBE_LENGTH)
                    .orElse(new byte[0]);
            String detectedMimeType = MediaContentValidator.detectMimeType(leadingBytes);
            if (detectedMimeType == null) {
                mediaRepository.markFailed(media.getId());
                log.warn("MEDIA-3.2 reconciliation: media {} failed - content not recognized", media.getId());
                continue;
            }

            boolean promoted = mediaRepository.finalizeReady(media.getId(), detectedMimeType, sizeBytes);
            if (promoted) {
                log.info("MEDIA-3.2 reconciliation: media {} promoted PENDING_UPLOAD -> READY " +
                        "(client never called finalize, object was already valid)", media.getId());
            }
        }
    }

    /**
     * CASE 4/5: DELETED rows still need their R2 object(s) removed. Deleting
     * an already-absent object is a safe, idempotent no-op for S3-compatible
     * storage, so this can run unconditionally every cycle without tracking
     * "already cleaned up" separately - a failure here (case 5) simply means
     * the same DELETED row is retried on the next cycle; the DB tombstone
     * (uploadStatus=DELETED) is never touched either way.
     *
     * MEDIA-3.4 Phase 7: also deletes thumbnailStorageKey when present - a
     * VIDEO row can have been fully processed (thumbnail generated and
     * persisted) before being soft-deleted, in which case the thumbnail
     * object needs the exact same best-effort cleanup as the canonical
     * video object. Purely additive to this existing case; no new
     * reconciliation subsystem, no bucket listing.
     */
    private void reconcileDeletedObjects() {
        List<DiagnosticMedia> deleted = mediaRepository.findByUploadStatus(UploadStatus.DELETED);
        for (DiagnosticMedia media : deleted) {
            try {
                storageClient.deleteObject(media.getStorageKey());
            } catch (Exception e) {
                log.warn("MEDIA-3.2 reconciliation: failed to delete R2 object for media {} - will retry next cycle: {}",
                        media.getId(), e.getMessage());
            }
            if (media.getThumbnailStorageKey() != null) {
                try {
                    storageClient.deleteObject(media.getThumbnailStorageKey());
                } catch (Exception e) {
                    log.warn("MEDIA-3.4 reconciliation: failed to delete R2 thumbnail object for media {} - will retry next cycle: {}",
                            media.getId(), e.getMessage());
                }
            }
            // MEDIA-HARDENING-1: a soft-deleted row can still carry an outstanding pending-
            // thumbnail marker (declared but never finalized before deletion raced in) - clean
            // it up here too, same best-effort pattern. Not clearing the DB marker itself: this
            // service only owns DELETED-row storage cleanup, not marker bookkeeping - the marker
            // column is harmless to leave populated on an already-DELETED, unreadable row (every
            // read/orphan-sweep path already gates on uploadStatus/processingStatus first).
            if (media.getPendingThumbnailStorageKey() != null) {
                try {
                    storageClient.deleteObject(media.getPendingThumbnailStorageKey());
                } catch (Exception e) {
                    log.warn("MEDIA-HARDENING-1 reconciliation: failed to delete R2 pending-thumbnail object for media {} - will retry next cycle: {}",
                            media.getId(), e.getMessage());
                }
            }
        }
    }

    /**
     * CASE 3: DB says READY but the object is missing. Deliberately does NOT
     * mutate the row (no silent recreation, no automatic downgrade to
     * FAILED) - a READY row losing its backing object is a genuine
     * data-integrity incident that needs investigation, not an automatic
     * state transition that could itself be wrong (e.g. a transient storage
     * blip). Logged loudly so it is observable.
     */
    private void checkReadyObjectsExist() {
        List<DiagnosticMedia> ready = mediaRepository.findByUploadStatus(UploadStatus.READY);
        for (DiagnosticMedia media : ready) {
            if (storageClient.headObject(media.getStorageKey()).isEmpty()) {
                log.error("MEDIA-3.2 reconciliation: media {} is READY in the database but its object is " +
                        "MISSING from storage (storageKey={}) - requires investigation, not auto-mutated",
                        media.getId(), media.getStorageKey());
            }
        }
    }
}
