package com.example.autoauction.identity.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KYC-SLOT-01 - per-user capture slot limit")
class IdentityCaptureRateLimiterTest {

    @Test
    @DisplayName("the first 30 slots in an hour are issued, the 31st is refused")
    void limit_refusesAfterHourlyCapacity() {
        IdentityCaptureRateLimiter limiter = new IdentityCaptureRateLimiter();

        assertThat(IntStream.range(0, IdentityCaptureRateLimiter.SLOTS_PER_HOUR).allMatch(i -> limiter.tryAcquire(1L))).isTrue();
        assertThat(limiter.tryAcquire(1L)).isFalse();
    }

    @Test
    @DisplayName("an exhausted limit of one user does not affect another user")
    void limit_isPerUser() {
        IdentityCaptureRateLimiter limiter = new IdentityCaptureRateLimiter();
        IntStream.range(0, IdentityCaptureRateLimiter.SLOTS_PER_HOUR + 5).forEach(i -> limiter.tryAcquire(1L));

        assertThat(limiter.tryAcquire(1L)).isFalse();
        assertThat(limiter.tryAcquire(2L)).isTrue();
    }

    @Test
    @DisplayName("eviction keeps recently used buckets - an exhausted user is not reset by it")
    void eviction_keepsRecentBuckets() {
        IdentityCaptureRateLimiter limiter = new IdentityCaptureRateLimiter();
        IntStream.range(0, IdentityCaptureRateLimiter.SLOTS_PER_HOUR).forEach(i -> limiter.tryAcquire(1L));

        limiter.evictStaleBuckets();

        assertThat(limiter.bucketCount()).isEqualTo(1);
        assertThat(limiter.tryAcquire(1L)).isFalse();
    }
}
