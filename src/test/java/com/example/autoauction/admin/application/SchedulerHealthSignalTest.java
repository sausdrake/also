package com.example.autoauction.admin.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 73 (G-N4): unit coverage for the in-memory scheduler health signal - success/failure
 * reporting semantics per scheduler id, independence between distinct ids, and thread-safety
 * under concurrent reporting (the real usage pattern, since every {@code @Scheduled} method runs
 * on Spring's shared scheduling pool and multiple scheduler beans report concurrently).
 */
@DisplayName("SchedulerHealthSignal")
class SchedulerHealthSignalTest {

    @Test
    @DisplayName("unknown scheduler id is simply absent from the snapshot")
    void unknownId_Absent() {
        SchedulerHealthSignal signal = new SchedulerHealthSignal();

        assertThat(signal.snapshot()).doesNotContainKey("nope");
    }

    @Test
    @DisplayName("reportSuccess records a success timestamp and clears the failed flag")
    void reportSuccess_RecordsSuccess() {
        SchedulerHealthSignal signal = new SchedulerHealthSignal();

        signal.reportSuccess("SchedA.tick");

        Map<String, SchedulerHealthSignal.SchedulerState> snapshot = signal.snapshot();
        SchedulerHealthSignal.SchedulerState state = snapshot.get("SchedA.tick");
        assertThat(state).isNotNull();
        assertThat(state.lastSuccessAt()).isNotNull();
        assertThat(state.lastFailureAt()).isNull();
        assertThat(state.lastExecutionFailed()).isFalse();
    }

    @Test
    @DisplayName("reportFailure records a failure timestamp and sets the failed flag")
    void reportFailure_RecordsFailure() {
        SchedulerHealthSignal signal = new SchedulerHealthSignal();

        signal.reportFailure("SchedA.tick");

        SchedulerHealthSignal.SchedulerState state = signal.snapshot().get("SchedA.tick");
        assertThat(state).isNotNull();
        assertThat(state.lastFailureAt()).isNotNull();
        assertThat(state.lastExecutionFailed()).isTrue();
    }

    @Test
    @DisplayName("a later success after a failure clears the failed flag but keeps the failure timestamp")
    void successAfterFailure_ClearsFlagKeepsTimestamp() {
        SchedulerHealthSignal signal = new SchedulerHealthSignal();

        signal.reportFailure("SchedA.tick");
        OffsetDateTime failureAt = signal.snapshot().get("SchedA.tick").lastFailureAt();
        signal.reportSuccess("SchedA.tick");

        SchedulerHealthSignal.SchedulerState state = signal.snapshot().get("SchedA.tick");
        assertThat(state.lastExecutionFailed()).isFalse();
        assertThat(state.lastSuccessAt()).isNotNull();
        assertThat(state.lastFailureAt()).isEqualTo(failureAt);
    }

    @Test
    @DisplayName("distinct scheduler ids do not interfere with each other")
    void distinctIds_Independent() {
        SchedulerHealthSignal signal = new SchedulerHealthSignal();

        signal.reportSuccess("SchedA.tick");
        signal.reportFailure("SchedB.tick");

        Map<String, SchedulerHealthSignal.SchedulerState> snapshot = signal.snapshot();
        assertThat(snapshot.get("SchedA.tick").lastExecutionFailed()).isFalse();
        assertThat(snapshot.get("SchedB.tick").lastExecutionFailed()).isTrue();
    }

    @Test
    @DisplayName("concurrent reporting from many threads never throws and leaves a consistent final state")
    void concurrentReporting_ThreadSafe() throws InterruptedException {
        SchedulerHealthSignal signal = new SchedulerHealthSignal();
        int threadCount = 16;
        int reportsPerThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int i = 0; i < reportsPerThread; i++) {
                        String id = "Sched" + (threadIndex % 4) + ".tick";
                        if (i % 2 == 0) {
                            signal.reportSuccess(id);
                        } else {
                            signal.reportFailure(id);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        Map<String, SchedulerHealthSignal.SchedulerState> snapshot = signal.snapshot();
        assertThat(snapshot).hasSize(4);
        snapshot.values().forEach(state -> {
            assertThat(state.lastSuccessAt()).isNotNull();
            assertThat(state.lastFailureAt()).isNotNull();
        });
    }
}
