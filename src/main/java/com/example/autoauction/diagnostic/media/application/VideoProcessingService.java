package com.example.autoauction.diagnostic.media.application;

import com.example.autoauction.diagnostic.media.domain.DiagnosticMedia;
import com.example.autoauction.diagnostic.media.domain.MediaType;
import com.example.autoauction.diagnostic.media.domain.ProcessingStatus;
import com.example.autoauction.diagnostic.media.domain.UploadStatus;
import com.example.autoauction.diagnostic.media.domain.port.DiagnosticMediaRepository;
import com.example.autoauction.diagnostic.media.domain.port.StorageClient;
import com.example.autoauction.diagnostic.media.domain.port.VideoProcessor;
import com.example.autoauction.diagnostic.media.web.dto.MediaResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * MEDIA-3.4 Phase 6: orchestrates video processing - claim, download,
 * VideoProcessor.process(), thumbnail upload/verify, atomic DB finalization.
 * Sequential and bounded only: no @Async, no ExecutorService, no
 * parallelStream, no queue - the existing single-threaded Spring scheduler
 * (a later phase) is the only concurrency this class needs to cooperate
 * with, and it already serializes every @Scheduled job in this app.
 *
 * CONFIGURATION NOTE: Phase 10 owns application.properties/Docker. The
 * limits below are deliberately isolated constants for THIS phase only -
 * not duplicated into FfmpegVideoProcessor, which never reads its own
 * configuration and only ever treats the ProcessingLimits built here as
 * authoritative (the Phase 5/6 architectural constraint).
 */
@Slf4j
@Service
public class VideoProcessingService {

    private static final int MAX_DURATION_SECONDS = 120;
    private static final int MAX_WIDTH = 1920;
    private static final int MAX_HEIGHT = 1080;
    private static final int THUMBNAIL_MAX_WIDTH = 320;
    private static final int THUMBNAIL_MAX_HEIGHT = 240;

    // MEDIA-3.4 Phase 7: same "Phase 10 owns configuration" isolation as the limits above -
    // comfortably longer than the FFmpeg adapter's own 30s/60s timeouts, so a legitimately
    // still-running (not crashed) claim is never mistaken for stale.
    private static final Duration STALE_PROCESSING_THRESHOLD = Duration.ofMinutes(30);

    private static final String THUMBNAIL_KEY_PREFIX = "diagnostic-media-thumbnail/";

    private final DiagnosticMediaRepository mediaRepository;
    private final StorageClient storageClient;
    private final VideoProcessor videoProcessor;

    public VideoProcessingService(DiagnosticMediaRepository mediaRepository, StorageClient storageClient, VideoProcessor videoProcessor) {
        this.mediaRepository = mediaRepository;
        this.storageClient = storageClient;
        this.videoProcessor = videoProcessor;
    }

    /**
     * Fetches at most batchSize PENDING/READY VIDEO candidates and processes
     * them sequentially. Never throws - one item's failure never aborts the
     * rest of the batch (mirrors LegacyMediaMigrationService's proven
     * per-item isolation).
     */
    public void processPending(int batchSize) {
        List<DiagnosticMedia> candidates = mediaRepository.findPendingProcessing(batchSize);
        for (DiagnosticMedia candidate : candidates) {
            try {
                processOne(candidate);
            } catch (Exception e) {
                log.error("VideoProcessingService: media {} - UNEXPECTED_ERROR outside the normal claim/process flow, isolated: {}",
                        candidate.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * MEDIA-3.4 Phase 7: recovers PROCESSING rows whose claim has gone stale
     * (worker/process crashed after claimForProcessing() but before
     * finalize/fail) - resets at most batchSize such rows back to PENDING so
     * they can be retried by a later processPending() call. Never touches
     * FAILED (resetStaleProcessing()'s WHERE clause only ever matches
     * PROCESSING - FAILED->PENDING remains the separate, explicit,
     * admin-triggered retry operation). Sequential, bounded, per-item
     * isolated - identical operational discipline to processPending().
     */
    public void reconcileStale(int batchSize) {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(STALE_PROCESSING_THRESHOLD);
        List<DiagnosticMedia> staleCandidates = mediaRepository.findStaleProcessing(cutoff, batchSize);
        for (DiagnosticMedia media : staleCandidates) {
            try {
                boolean reset = mediaRepository.resetStaleProcessing(media.getId());
                if (reset) {
                    log.warn("VideoProcessingService: media {} - STALE_PROCESSING_RESET (claimed at {}, older than {} minutes) -> PENDING",
                            media.getId(), media.getProcessingClaimedAt(), STALE_PROCESSING_THRESHOLD.toMinutes());
                } else {
                    log.info("VideoProcessingService: media {} - stale reset raced (row already left PROCESSING before reset ran), no-op",
                            media.getId());
                }
            } catch (Exception e) {
                log.error("VideoProcessingService: media {} - STALE_RECOVERY_FAILURE, isolated: {}", media.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * MEDIA-3.4 Phase 9: admin-triggered FAILED -> PENDING retry. Never
     * called automatically - processPending()/reconcileStale() never invoke
     * this; it exists solely for the admin HTTP endpoint. Idempotent from
     * the caller's perspective: retrying an already-PENDING row is a safe
     * no-op (current state returned, no error) - the same idempotency shape
     * as DiagnosticMediaService.finalize()'s established precedent for an
     * already-settled row. DELETED/PROCESSING/READY are genuine conflicts,
     * not no-ops, and are rejected.
     */
    public MediaResponse retryProcessing(Long mediaId) {
        DiagnosticMedia media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new IllegalArgumentException("Медиафайл не найден: " + mediaId));

        MediaResponse noop = classifyForRetry(media);
        if (noop != null) {
            return noop;
        }

        // classifyForRetry() only returns null for FAILED (uploadStatus already confirmed READY
        // there, since DELETED is the only other reachable uploadStatus once processingStatus
        // has ever left NOT_REQUIRED/PENDING - see classifyForRetry's Javadoc). This is the ONLY
        // state from which retryFailedProcessing() is ever called.
        boolean retried = mediaRepository.retryFailedProcessing(mediaId);
        if (retried) {
            DiagnosticMedia after = mediaRepository.findById(mediaId)
                    .orElseThrow(() -> new IllegalArgumentException("Медиафайл не найден: " + mediaId));
            log.info("VideoProcessingService: media {} - admin retry succeeded (FAILED -> PENDING)", mediaId);
            return MediaResponse.fromDomain(after);
        }

        // retryFailedProcessing()'s boolean is overloaded - false does not by itself mean
        // "conflict". Re-read the authoritative state instead of assuming failure: another
        // actor may have already resolved this (e.g. a concurrent retry request winning the
        // same atomic UPDATE) between our check above and this call.
        DiagnosticMedia after = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new IllegalArgumentException("Медиафайл не найден: " + mediaId));
        MediaResponse afterNoop = classifyForRetry(after);
        if (afterNoop != null) {
            log.info("VideoProcessingService: media {} - retry raced with a concurrent change, resolved to {}",
                    mediaId, after.getProcessingStatus());
            return afterNoop;
        }
        // Still FAILED+READY despite a failed atomic UPDATE attempt - a genuine failure to
        // retry, not a race resolved by someone else. Surface it, do not silently claim success.
        log.warn("VideoProcessingService: media {} - retryFailedProcessing() returned false but state is still FAILED+READY",
                mediaId);
        throw new IllegalStateException("Не удалось запустить повторную обработку медиафайла " + mediaId
                + ": состояние не изменилось (processingStatus=" + after.getProcessingStatus() + ")");
    }

    /**
     * Classifies mediaId's CURRENT state for a retry request. Returns a
     * MediaResponse if the state is the idempotent PENDING no-op; throws
     * IllegalStateException if it is a genuine conflict (DELETED, or
     * processingStatus PROCESSING/READY/anything but FAILED); returns null
     * only for the one state retryProcessing() should actually act on:
     * processingStatus=FAILED (uploadStatus is necessarily READY at that
     * point - DELETED is checked first, and no other uploadStatus is
     * reachable once processingStatus has ever left NOT_REQUIRED/PENDING,
     * since claimForProcessing()'s own WHERE clause requires
     * uploadStatus=READY and uploadStatus never transitions away from READY
     * except to DELETED).
     */
    private MediaResponse classifyForRetry(DiagnosticMedia media) {
        if (media.getUploadStatus() == UploadStatus.DELETED) {
            throw new IllegalStateException("Медиафайл удалён, повторная обработка недоступна: " + media.getId());
        }
        if (media.getProcessingStatus() == ProcessingStatus.PENDING) {
            return MediaResponse.fromDomain(media);
        }
        if (media.getProcessingStatus() == ProcessingStatus.PROCESSING) {
            throw new IllegalStateException("Медиафайл сейчас обрабатывается, повторная обработка недоступна: " + media.getId());
        }
        if (media.getProcessingStatus() == ProcessingStatus.READY) {
            throw new IllegalStateException("Медиафайл уже успешно обработан, повторная обработка недоступна: " + media.getId());
        }
        if (media.getProcessingStatus() != ProcessingStatus.FAILED) {
            throw new IllegalStateException("Повторная обработка доступна только для видео в статусе FAILED. Текущий статус: "
                    + media.getProcessingStatus());
        }
        return null;
    }

    private void processOne(DiagnosticMedia candidate) {
        // findPendingProcessing() filters by uploadStatus=READY/processingStatus=PENDING at the
        // DB level, and claimForProcessing()'s own WHERE clause re-verifies both atomically at
        // claim time - re-querying either here would reintroduce a SELECT-then-UPDATE race, so
        // it is deliberately NOT re-checked. mediaType is the one invariant the query does not
        // enforce (PHOTO rows are never PENDING under normal operation, but this is checked
        // explicitly rather than assumed).
        if (candidate.getMediaType() != MediaType.VIDEO) {
            log.error("VideoProcessingService: media {} is not VIDEO (mediaType={}) - invariant violation, skipping without claiming",
                    candidate.getId(), candidate.getMediaType());
            return;
        }

        boolean claimed;
        try {
            claimed = mediaRepository.claimForProcessing(candidate.getId());
        } catch (Exception e) {
            log.error("VideoProcessingService: media {} - claim attempt itself threw, skipping: {}", candidate.getId(), e.getMessage(), e);
            return;
        }
        if (!claimed) {
            log.info("VideoProcessingService: media {} - CLAIM_LOST/CLAIM_SKIPPED (already claimed or state changed concurrently)",
                    candidate.getId());
            return;
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("video-processing-" + candidate.getId() + "-");
            processClaimedItem(candidate, tempDir);
        } catch (Exception e) {
            log.error("VideoProcessingService: media {} - UNEXPECTED_ERROR after claim, isolated: {}", candidate.getId(), e.getMessage(), e);
            failClaim(candidate.getId(), "UNEXPECTED_ERROR", e);
        } finally {
            cleanupTempDirectory(tempDir, candidate.getId());
        }
    }

    private void processClaimedItem(DiagnosticMedia media, Path tempDir) {
        Path videoFile = tempDir.resolve("video");

        try {
            storageClient.downloadToFile(media.getStorageKey(), videoFile);
        } catch (Exception e) {
            // Case A: no thumbnail was ever generated/uploaded - nothing to clean up in R2.
            failClaim(media.getId(), "DOWNLOAD_FAILED", e);
            return;
        }

        VideoProcessor.ProcessingResult result;
        try {
            result = videoProcessor.process(videoFile, buildProcessingLimits());
        } catch (Exception e) {
            // Case A: failure happened before any thumbnail upload was attempted.
            failClaim(media.getId(), "PROCESSING_FAILED", e);
            return;
        }

        String invalidReason = validateResult(result, tempDir);
        if (invalidReason != null) {
            // Defensive - do not trust VideoProcessor's own validation alone. Case A: still
            // before any upload, so no R2 cleanup is needed even if a (rejected) thumbnail
            // file exists locally - the whole temp directory is deleted in processOne's finally.
            log.error("VideoProcessingService: media {} - INVALID_PROCESSING_RESULT: {}", media.getId(), invalidReason);
            failClaim(media.getId(), "INVALID_PROCESSING_RESULT", null);
            return;
        }

        String thumbnailStorageKey = THUMBNAIL_KEY_PREFIX + media.getDiagnosticRequestId() + "/" + UUID.randomUUID() + ".jpg";

        try (InputStream in = Files.newInputStream(result.thumbnailFile())) {
            storageClient.putObject(thumbnailStorageKey, in, Files.size(result.thumbnailFile()), "image/jpeg");
        } catch (Exception e) {
            // Case A: putObject's contract is all-or-nothing (throws rather than partially
            // storing), so no compensating delete is attempted here.
            failClaim(media.getId(), "THUMBNAIL_UPLOAD_FAILED", e);
            return;
        }

        Optional<StorageClient.StoredObjectMetadata> headResult;
        try {
            headResult = storageClient.headObject(thumbnailStorageKey);
        } catch (Exception e) {
            // Case B: upload succeeded, verification threw.
            compensatingDelete(thumbnailStorageKey, media.getId());
            failClaim(media.getId(), "THUMBNAIL_VERIFICATION_FAILED", e);
            return;
        }
        if (headResult.isEmpty()) {
            // Case B: upload "succeeded" but the object isn't actually there.
            compensatingDelete(thumbnailStorageKey, media.getId());
            failClaim(media.getId(), "THUMBNAIL_VERIFICATION_FAILED", null);
            return;
        }

        boolean finalized;
        try {
            finalized = mediaRepository.finalizeProcessingReady(
                    media.getId(), result.durationSeconds(), result.width(), result.height(), thumbnailStorageKey);
        } catch (Exception e) {
            // Case D: DB finalization threw. MEDIA-3.3 pattern - the original DB failure is the
            // primary failure; a compensating-delete failure must never replace/mask it.
            log.error("VideoProcessingService: media {} - DB_FINALIZATION_FAILED (threw) for thumbnail {}: {}",
                    media.getId(), thumbnailStorageKey, e.getMessage(), e);
            try {
                storageClient.deleteObject(thumbnailStorageKey);
            } catch (Exception deleteEx) {
                log.error("VideoProcessingService: media {} - compensating delete of {} ALSO failed, possible orphan: {}",
                        media.getId(), thumbnailStorageKey, deleteEx.getMessage(), deleteEx);
            }
            try {
                mediaRepository.markProcessingFailed(media.getId());
            } catch (Exception markEx) {
                log.error("VideoProcessingService: media {} - markProcessingFailed after DB_FINALIZATION_FAILED also threw: {}",
                        media.getId(), markEx.getMessage(), markEx);
            }
            return;
        }

        if (!finalized) {
            // Case C: DB finalization returned false - the row is no longer PROCESSING+READY
            // (most likely soft-deleted mid-processing). Do NOT call markProcessingFailed: the
            // row has already legitimately left PROCESSING, so there is nothing to "fail" -
            // doing so would be an incorrect state transition, not a race-safe no-op.
            log.warn("VideoProcessingService: media {} - finalizeProcessingReady returned false (row no longer " +
                    "PROCESSING+READY) - discarding result, deleting thumbnail {}", media.getId(), thumbnailStorageKey);
            compensatingDelete(thumbnailStorageKey, media.getId());
            return;
        }

        log.info("VideoProcessingService: media {} processed successfully (duration={}s, {}x{}, thumbnail={})",
                media.getId(), result.durationSeconds(), result.width(), result.height(), thumbnailStorageKey);
    }

    /**
     * Defensive re-validation of the port's result - never trust
     * VideoProcessor's own internal checks alone. Path-containment uses the
     * same toRealPath()-based, symlink-resistant guard already proven in
     * LegacyMediaMigrationService (MEDIA-3.3).
     */
    private String validateResult(VideoProcessor.ProcessingResult result, Path tempDir) {
        if (result.durationSeconds() <= 0) {
            return "durationSeconds must be positive: " + result.durationSeconds();
        }
        if (result.width() <= 0) {
            return "width must be positive: " + result.width();
        }
        if (result.height() <= 0) {
            return "height must be positive: " + result.height();
        }
        if (result.durationSeconds() > MAX_DURATION_SECONDS) {
            return "durationSeconds exceeds configured maximum: " + result.durationSeconds();
        }
        if (result.width() > MAX_WIDTH) {
            return "width exceeds configured maximum: " + result.width();
        }
        if (result.height() > MAX_HEIGHT) {
            return "height exceeds configured maximum: " + result.height();
        }

        Path thumbnail = result.thumbnailFile();
        if (thumbnail == null) {
            return "thumbnailFile is null";
        }
        if (!Files.exists(thumbnail)) {
            return "thumbnailFile does not exist: " + thumbnail;
        }
        if (!Files.isRegularFile(thumbnail)) {
            return "thumbnailFile is not a regular file: " + thumbnail;
        }
        if (!Files.isReadable(thumbnail)) {
            return "thumbnailFile is not readable: " + thumbnail;
        }
        long size;
        try {
            size = Files.size(thumbnail);
        } catch (IOException e) {
            return "failed to read thumbnailFile size: " + e.getMessage();
        }
        if (size <= 0) {
            return "thumbnailFile is empty";
        }

        try {
            Path realThumbnail = thumbnail.toRealPath();
            Path realTempDir = tempDir.toRealPath();
            if (!realThumbnail.startsWith(realTempDir)) {
                return "thumbnailFile resolves outside the temp directory (path traversal/symlink?): " + thumbnail;
            }
        } catch (IOException e) {
            return "failed to resolve real path for thumbnailFile: " + e.getMessage();
        }

        return null;
    }

    private VideoProcessor.ProcessingLimits buildProcessingLimits() {
        return new VideoProcessor.ProcessingLimits(MAX_DURATION_SECONDS, MAX_WIDTH, MAX_HEIGHT, THUMBNAIL_MAX_WIDTH, THUMBNAIL_MAX_HEIGHT);
    }

    /** Never throws - a failed failure-marking attempt must not abort the batch. */
    private void failClaim(Long mediaId, String category, Exception cause) {
        if (cause != null) {
            log.error("VideoProcessingService: media {} - {}: {}", mediaId, category, cause.getMessage(), cause);
        } else {
            log.error("VideoProcessingService: media {} - {}", mediaId, category);
        }
        try {
            boolean marked = mediaRepository.markProcessingFailed(mediaId);
            if (!marked) {
                log.warn("VideoProcessingService: media {} - markProcessingFailed returned false (state already changed concurrently)", mediaId);
            }
        } catch (Exception markEx) {
            log.error("VideoProcessingService: media {} - markProcessingFailed itself threw, batch continues: {}",
                    mediaId, markEx.getMessage(), markEx);
        }
    }

    /** Best-effort - a delete failure is logged, never allowed to mask the caller's original failure. */
    private void compensatingDelete(String storageKey, Long mediaId) {
        try {
            storageClient.deleteObject(storageKey);
        } catch (Exception e) {
            log.error("VideoProcessingService: media {} - compensating delete of {} failed, possible orphan: {}",
                    mediaId, storageKey, e.getMessage(), e);
        }
    }

    /**
     * Recursively deletes the entire per-item temp directory - never just the known
     * video/thumbnail files - covering every exit path (success, any failure category,
     * unexpected exception). Cleanup failures are logged only; they never replace or mask
     * whatever processing outcome already occurred.
     */
    private void cleanupTempDirectory(Path tempDir, Long mediaId) {
        if (tempDir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    log.warn("VideoProcessingService: media {} - failed to delete temp path {} during cleanup: {}",
                            mediaId, path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("VideoProcessingService: media {} - failed to walk temp directory {} for cleanup: {}",
                    mediaId, tempDir, e.getMessage());
        }
    }
}
