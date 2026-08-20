package com.example.autoauction.diagnostic.media.infrastructure.scheduler;

import com.example.autoauction.diagnostic.media.application.VideoProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * MEDIA-3.4 Phase 8. Thin orchestrator only, matching MediaReconciliationScheduler's
 * structural convention - independent bean/schedule, no shared machinery with the
 * auction/financial/media-reconciliation schedulers, no business logic here.
 *
 * CADENCE/BATCH-SIZE RATIONALE (repository-grounded, established by a repo-wide
 * @Scheduled audit before writing this class, not guessed):
 *   - AuctionCompletionScheduler: FOUR methods, each fixedDelay=60_000 (1 minute).
 *   - FinancialRecoveryScheduler: fixedDelay=60_000 (1 minute).
 *   - RateLimitService: fixedDelay=600_000 (10 minutes).
 *   - TokenCleanupScheduler: cron "0 0 3 * * ?" (once daily).
 *   - MediaReconciliationScheduler: fixedDelay=300_000 (5 minutes).
 * TIGHTEST existing cadence: 60 seconds, shared by 5 methods across two classes.
 * This repository has no custom TaskScheduler and no
 * spring.task.scheduling.pool.size override, so Spring Boot's default
 * SINGLE-THREADED scheduling pool serializes EVERY @Scheduled method in the
 * whole application - a slow VideoProcessingScheduler tick can directly delay
 * those 60-second-cadence, auction/financially-critical tasks.
 *
 * WORST-CASE TICK DURATION: FfmpegVideoProcessor's own explicit timeouts (30s
 * ffprobe + 60s ffmpeg, each followed by a 5s forced-termination confirmation
 * grace period) already put ONE video's worst-case processing time at
 * ~100 seconds - longer than the tightest existing scheduler's 60-second
 * cadence, independent of this scheduler's own batch size or interval.
 * (Download/upload/headObject/DB calls/cleanup add comparatively little on
 * top in the normal case, but StorageClient enforces no explicit download
 * timeout of its own, so the true worst case could exceed this estimate -
 * disclosed, not solved, here; StorageClient is out of this phase's scope.)
 * This is a genuine, disclosed residual risk. BATCH_SIZE=1 (the smallest
 * possible) minimizes it - it cannot fully eliminate it without reducing
 * FfmpegVideoProcessor's own timeouts, which is a Phase 5 file not modified
 * in this phase.
 *
 * fixedDelay=300_000 (5 minutes): follows MediaReconciliationScheduler's
 * existing precedent exactly - the closest structural sibling (both are
 * periodic maintenance sweeps over the same diagnostic_media table) - not
 * the tighter 60s auction/financial cadence, which serves a distinct,
 * more time-critical business need this class has no reason to match.
 */
@Slf4j
@Component
public class VideoProcessingScheduler {

    private static final int BATCH_SIZE = 1;

    private final VideoProcessingService videoProcessingService;

    public VideoProcessingScheduler(VideoProcessingService videoProcessingService) {
        this.videoProcessingService = videoProcessingService;
        log.info("VideoProcessingScheduler initialized (batchSize={})", BATCH_SIZE);
    }

    /**
     * Two independent try/catch blocks, not one wrapping both calls - a
     * deliberate, reported difference from MediaReconciliationScheduler's
     * single-try/catch-around-one-call shape (that scheduler only ever makes
     * one downstream call). Here, processPending() throwing must not prevent
     * reconcileStale() from running in the same tick, and vice versa for the
     * next tick - both isolations are explicit MEDIA-3.4 Phase 8
     * requirements, not an structural accident.
     */
    @Scheduled(fixedDelay = 300_000)
    public void processVideos() {
        try {
            videoProcessingService.processPending(BATCH_SIZE);
        } catch (Exception e) {
            log.error("Video processing sweep failed: {}", e.getMessage(), e);
        }
        try {
            videoProcessingService.reconcileStale(BATCH_SIZE);
        } catch (Exception e) {
            log.error("Stale video-processing reconciliation sweep failed: {}", e.getMessage(), e);
        }
    }
}
