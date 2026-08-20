package com.example.autoauction.diagnostic.media.infrastructure.scheduler;

import com.example.autoauction.diagnostic.media.application.VideoProcessingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service);

        scheduler.processVideos();

        verify(service).processPending(eq(EXPECTED_BATCH_SIZE));
    }

    @Test
    @DisplayName("2: планировщик вызывает reconcileStale(batchSize)")
    void invokesReconcileStaleWithConfiguredBatchSize() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service);

        scheduler.processVideos();

        verify(service).reconcileStale(eq(EXPECTED_BATCH_SIZE));
    }

    @Test
    @DisplayName("3: сбой processPending перехватывается, reconcileStale всё равно выполняется в этом же тике")
    void processPendingThrows_ReconcileStaleStillRunsSameTick() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        doThrow(new RuntimeException("simulated processPending failure")).when(service).processPending(anyInt());
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service);

        scheduler.processVideos(); // must not throw

        verify(service).reconcileStale(anyInt());
    }

    @Test
    @DisplayName("4: сбой reconcileStale перехватывается, последующие вызовы планировщика остаются возможны")
    void reconcileStaleThrows_FutureInvocationsRemainPossible() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        doThrow(new RuntimeException("simulated reconcileStale failure")).when(service).reconcileStale(anyInt());
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service);

        scheduler.processVideos(); // must not throw
        scheduler.processVideos(); // a second tick must still run normally

        verify(service, times(2)).processPending(anyInt());
        verify(service, times(2)).reconcileStale(anyInt());
    }

    @Test
    @DisplayName("5: одинаковый сконфигурированный batchSize передаётся в оба вызова")
    void sameBatchSizePassedToBothCalls() {
        VideoProcessingService service = mock(VideoProcessingService.class);
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service);

        scheduler.processVideos();

        verify(service).processPending(EXPECTED_BATCH_SIZE);
        verify(service).reconcileStale(EXPECTED_BATCH_SIZE);
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
        VideoProcessingScheduler scheduler = new VideoProcessingScheduler(service);

        scheduler.processVideos();

        verify(service, times(1)).processPending(anyInt());
        verify(service, times(1)).reconcileStale(anyInt());
    }
}
