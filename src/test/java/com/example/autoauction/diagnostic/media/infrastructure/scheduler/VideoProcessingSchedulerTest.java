package com.example.autoauction.diagnostic.media.infrastructure.scheduler;

import com.example.autoauction.diagnostic.media.application.VideoProcessingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * MEDIA-3.4 Phase 8: VideoProcessingScheduler. No existing dedicated
 * scheduler-test convention exists in this repository (MediaReconciliationScheduler,
 * the closest structural sibling, has none either - only its underlying
 * service is tested) - per the phase instructions, this is the smallest
 * plain JUnit + Mockito test that proves delegation and exception
 * containment, with no Spring context needed at all.
 */
@DisplayName("MEDIA-3.4: VideoProcessingScheduler")
class VideoProcessingSchedulerTest {

    private static final int EXPECTED_BATCH_SIZE = 1;

    @Test
    @DisplayName("1: планировщик вызывает processPending(batchSize)")
    void invokesProcessPendingWithConfiguredBatchSize() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service, EXPECTED_BATCH_SIZE);

        scheduler.processVideos();

        verify(service).processPending(eq(EXPECTED_BATCH_SIZE));
    }

    @Test
    @DisplayName("2: планировщик вызывает reconcileStale(batchSize)")
    void invokesReconcileStaleWithConfiguredBatchSize() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service, EXPECTED_BATCH_SIZE);

        scheduler.processVideos();

        verify(service).reconcileStale(eq(EXPECTED_BATCH_SIZE));
    }

    @Test
    @DisplayName("3: сбой processPending перехватывается, reconcileStale всё равно выполняется в этом же тике")
    void processPendingThrows_ReconcileStaleStillRunsSameTick() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        doThrow(new RuntimeException("simulated processPending failure")).when(service).processPending(anyInt());
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service, EXPECTED_BATCH_SIZE);

        scheduler.processVideos(); // must not throw

        verify(service).reconcileStale(anyInt());
    }

    @Test
    @DisplayName("4: сбой reconcileStale перехватывается, последующие вызовы планировщика остаются возможны")
    void reconcileStaleThrows_FutureInvocationsRemainPossible() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        doThrow(new RuntimeException("simulated reconcileStale failure")).when(service).reconcileStale(anyInt());
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service, EXPECTED_BATCH_SIZE);

        scheduler.processVideos(); // must not throw
        scheduler.processVideos(); // a second tick must still run normally

        verify(service, times(2)).processPending(anyInt());
        verify(service, times(2)).reconcileStale(anyInt());
    }

    @Test
    @DisplayName("5: одинаковый сконфигурированный batchSize передаётся в оба вызова")
    void sameBatchSizePassedToBothCalls() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service, EXPECTED_BATCH_SIZE);

        scheduler.processVideos();

        verify(service).processPending(EXPECTED_BATCH_SIZE);
        verify(service).reconcileStale(EXPECTED_BATCH_SIZE);
    }

    @Test
    @DisplayName("12 (Phase 10): нестандартный сконфигурированный batchSize передаётся в оба вызова (не только значение по умолчанию 1)")
    void nonDefaultConfiguredBatchSizePassedToBothCalls() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service, 3);

        scheduler.processVideos();

        verify(service).processPending(3);
        verify(service).reconcileStale(3);
    }

    @Test
    @DisplayName("13 (Phase 10): fixedDelay читается из diagnostic.media.processing.scheduler.fixed-delay-ms, а не захардкожен")
    void fixedDelayIsPropertyDriven() throws NoSuchMethodException {
        Method processVideos = VideoProcessingScheduler.class.getDeclaredMethod("processVideos");
        Scheduled scheduled = processVideos.getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString()).isEqualTo("${diagnostic.media.processing.scheduler.fixed-delay-ms}");
    }

    @Test
    @DisplayName("14 (Phase 10): batchSize < 1 отклоняется при старте (validateBatchSize, @PostConstruct)")
    void invalidBatchSizeRejectedAtStartup() {
        VideoProcessingService service = mock(VideoProcessingService.class);

        VideoProcessingScheduler zero = new VideoProcessingScheduler(service, 0);
        assertThatThrownBy(zero::validateBatchSize).isInstanceOf(IllegalStateException.class);

        VideoProcessingScheduler negative = new VideoProcessingScheduler(service, -1);
        assertThatThrownBy(negative::validateBatchSize).isInstanceOf(IllegalStateException.class);

        VideoProcessingScheduler valid = new VideoProcessingScheduler(service, 1);
        valid.validateBatchSize(); // must not throw
    }

    @Test
    @DisplayName("6: класс не содержит параллельных механизмов выполнения (ровно один @Scheduled метод)")
    void noParallelExecutionMechanism() {
        long scheduledMethodCount = Arrays.stream(VideoProcessingScheduler.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class))
                .count();
        assertThat(scheduledMethodCount).isEqualTo(1);

        // No @Async anywhere on the class either.
        boolean noAsyncMethods = Arrays.stream(VideoProcessingScheduler.class.getDeclaredMethods())
                .noneMatch(m -> m.isAnnotationPresent(org.springframework.scheduling.annotation.Async.class));
        assertThat(noAsyncMethods).isTrue();
    }

    @Test
    @DisplayName("7: один вызов processVideos() вызывает каждый сервисный метод ровно один раз")
    void singleTickInvokesEachServiceMethodExactlyOnce() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service, EXPECTED_BATCH_SIZE);

        scheduler.processVideos();

        verify(service, times(1)).processPending(anyInt());
        verify(service, times(1)).reconcileStale(anyInt());
    }

    @Test
    @DisplayName("11 (Phase 9): планировщик никогда не вызывает retryProcessing() - FAILED->PENDING остаётся только ручной admin-операцией")
    void neverCallsRetryProcessing() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service, EXPECTED_BATCH_SIZE);

        scheduler.processVideos();

        verify(service, never()).retryProcessing(anyLong());
    }

    // ==================== MEDIA-HARDENING-1: reconcilePendingThumbnailOrphans wiring ====================

    @Test
    @DisplayName("15 (MEDIA-HARDENING-1): планировщик вызывает reconcilePendingThumbnailOrphans(batchSize)")
    void invokesReconcilePendingThumbnailOrphansWithConfiguredBatchSize() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service, EXPECTED_BATCH_SIZE);

        scheduler.processVideos();

        verify(service).reconcilePendingThumbnailOrphans(eq(EXPECTED_BATCH_SIZE));
    }

    @Test
    @DisplayName("16 (MEDIA-HARDENING-1): один вызов processVideos() вызывает все три сервисных метода ровно один раз")
    void singleTickInvokesAllThreeServiceMethodsExactlyOnce() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service, EXPECTED_BATCH_SIZE);

        scheduler.processVideos();

        verify(service, times(1)).processPending(anyInt());
        verify(service, times(1)).reconcileStale(anyInt());
        verify(service, times(1)).reconcilePendingThumbnailOrphans(anyInt());
    }

    @Test
    @DisplayName("17 (MEDIA-HARDENING-1): сбой processPending перехватывается, reconcileStale и reconcilePendingThumbnailOrphans всё равно выполняются в этом же тике")
    void processPendingThrows_ReconcileStaleAndPendingThumbnailOrphansStillRunSameTick() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        doThrow(new RuntimeException("simulated processPending failure")).when(service).processPending(anyInt());
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service, EXPECTED_BATCH_SIZE);

        scheduler.processVideos(); // must not throw

        verify(service).reconcileStale(anyInt());
        verify(service).reconcilePendingThumbnailOrphans(anyInt());
    }

    @Test
    @DisplayName("18 (MEDIA-HARDENING-1): сбой reconcileStale перехватывается, processPending и reconcilePendingThumbnailOrphans всё равно выполняются в этом же тике")
    void reconcileStaleThrows_ProcessPendingAndPendingThumbnailOrphansStillRunSameTick() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        doThrow(new RuntimeException("simulated reconcileStale failure")).when(service).reconcileStale(anyInt());
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service, EXPECTED_BATCH_SIZE);

        scheduler.processVideos(); // must not throw

        verify(service).processPending(anyInt());
        verify(service).reconcilePendingThumbnailOrphans(anyInt());
    }

    @Test
    @DisplayName("19 (MEDIA-HARDENING-1): сбой reconcilePendingThumbnailOrphans перехватывается, processPending и reconcileStale всё равно выполняются в этом же тике")
    void reconcilePendingThumbnailOrphansThrows_ProcessPendingAndReconcileStaleStillRunSameTick() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        doThrow(new RuntimeException("simulated reconcilePendingThumbnailOrphans failure"))
                .when(service).reconcilePendingThumbnailOrphans(anyInt());
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service, EXPECTED_BATCH_SIZE);

        scheduler.processVideos(); // must not throw

        verify(service).processPending(anyInt());
        verify(service).reconcileStale(anyInt());
    }

    @Test
    @DisplayName("20 (MEDIA-HARDENING-1): сбой reconcilePendingThumbnailOrphans не блокирует последующие тики")
    void reconcilePendingThumbnailOrphansThrows_FutureInvocationsRemainPossible() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        doThrow(new RuntimeException("simulated reconcilePendingThumbnailOrphans failure"))
                .when(service).reconcilePendingThumbnailOrphans(anyInt());
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service, EXPECTED_BATCH_SIZE);

        scheduler.processVideos(); // must not throw
        scheduler.processVideos(); // a second tick must still run normally

        verify(service, times(2)).processPending(anyInt());
        verify(service, times(2)).reconcileStale(anyInt());
        verify(service, times(2)).reconcilePendingThumbnailOrphans(anyInt());
    }

    @Test
    @DisplayName("21 (MEDIA-HARDENING-1): сконфигурированный batchSize передаётся в reconcilePendingThumbnailOrphans, как и в остальные два вызова")
    void sameBatchSizePassedToReconcilePendingThumbnailOrphans() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service, EXPECTED_BATCH_SIZE);

        scheduler.processVideos();

        verify(service).processPending(EXPECTED_BATCH_SIZE);
        verify(service).reconcileStale(EXPECTED_BATCH_SIZE);
        verify(service).reconcilePendingThumbnailOrphans(EXPECTED_BATCH_SIZE);
    }

    @Test
    @DisplayName("22 (MEDIA-HARDENING-1): нестандартный сконфигурированный batchSize передаётся в reconcilePendingThumbnailOrphans")
    void nonDefaultConfiguredBatchSizePassedToReconcilePendingThumbnailOrphans() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service, 3);

        scheduler.processVideos();

        verify(service).reconcilePendingThumbnailOrphans(3);
    }
}
