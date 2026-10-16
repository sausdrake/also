package com.example.autoauction.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-04: TestUserIds hands out distinct, increasing ids above every other test user id, from
 * any number of threads. Only relative properties are checked: other test classes in the same
 * JVM draw from the same counter, so the absolute values are unknown.
 */
@DisplayName("TEST-04: TestUserIds - unique synthetic test user ids")
class TestUserIdsTest {

    /** The largest hard-coded test user id; the nanoTime-derived fixture ids stay below 101 000 000. */
    private static final long HIGHEST_OTHER_TEST_USER_ID = 1_000_000_000L;

    @Test
    @DisplayName("100 000 sequential calls: all distinct, none below FIRST, never decreasing")
    void sequentialCalls_distinctInNamespaceNeverDecreasing() {
        long[] ids = draw(100_000);

        assertThat(belowFirst(ids)).as("ids below FIRST").isZero();
        assertThat(decreases(ids)).as("ids smaller than their predecessor").isZero();
        assertThat(duplicates(ids)).as("duplicate ids").isZero();
    }

    @Test
    @DisplayName("16 threads x 50 000 calls, started together: 800 000 distinct ids, none below FIRST")
    void concurrentCalls_allDistinct() throws Exception {
        int threads = 16;
        int perThread = 50_000;
        long[][] drawn = new long[threads][];
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int slot = t;
                done.add(pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    drawn[slot] = draw(perThread);
                    return null;
                }));
            }
            for (Future<?> f : done) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        long[] all = Arrays.stream(drawn).flatMapToLong(Arrays::stream).toArray();

        assertThat(all).hasSize(threads * perThread);
        assertThat(belowFirst(all)).as("ids below FIRST").isZero();
        assertThat(duplicates(all)).as("duplicate ids").isZero();
    }

    @Test
    @DisplayName("one thread: every id is strictly greater than the previous one")
    void oneThread_strictlyIncreasing() {
        long[] ids = draw(10_000);

        long notGreater = 0;
        for (int i = 1; i < ids.length; i++) {
            if (ids[i] <= ids[i - 1]) {
                notGreater++;
            }
        }
        assertThat(notGreater).as("ids not greater than their predecessor").isZero();
    }

    @Test
    @DisplayName("FIRST lies above every other test user id and above the int range")
    void namespace_aboveOtherTestUserIdsAndIntRange() {
        assertThat(TestUserIds.FIRST)
                .isGreaterThan(HIGHEST_OTHER_TEST_USER_ID)
                .isGreaterThan(Integer.MAX_VALUE);
        assertThat(TestUserIds.next()).isGreaterThanOrEqualTo(TestUserIds.FIRST);
    }

    private static long[] draw(int count) {
        long[] ids = new long[count];
        for (int i = 0; i < count; i++) {
            ids[i] = TestUserIds.next();
        }
        return ids;
    }

    private static long belowFirst(long[] ids) {
        return Arrays.stream(ids).filter(id -> id < TestUserIds.FIRST).count();
    }

    private static long decreases(long[] ids) {
        long count = 0;
        for (int i = 1; i < ids.length; i++) {
            if (ids[i] < ids[i - 1]) {
                count++;
            }
        }
        return count;
    }

    private static long duplicates(long[] ids) {
        long[] sorted = ids.clone();
        Arrays.sort(sorted);
        long count = 0;
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i] == sorted[i - 1]) {
                count++;
            }
        }
        return count;
    }
}
