package com.example.autoauction.identity.application;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KYC-SLOT-01: per-user limit on identity capture slots. Every slot is a database row plus a presigned
 * upload URL; one complete KYC submission needs at most three (front, back, selfie), retries included a few
 * more. The limit only stops a client from minting slots in bulk - a technical abuse bound, not a business rule.
 */
@Component
public class IdentityCaptureRateLimiter {

    static final int SLOTS_PER_HOUR = 30;

    private final Map<Long, Entry> buckets = new ConcurrentHashMap<>();

    public boolean tryAcquire(Long userId) {
        Entry entry = buckets.compute(userId, (id, existing) -> {
            if (existing == null) return new Entry(newBucket());
            existing.lastUsed = Instant.now();
            return existing;
        });
        return entry.bucket.tryConsume(1);
    }

    /** Buckets unused for two hours are full again anyway - drop them. */
    @Scheduled(fixedDelay = 30 * 60_000)
    public void evictStaleBuckets() {
        Instant threshold = Instant.now().minus(Duration.ofHours(2));
        buckets.entrySet().removeIf(e -> e.getValue().lastUsed.isBefore(threshold));
    }

    int bucketCount() {
        return buckets.size();
    }

    private static Bucket newBucket() {
        return Bucket.builder().addLimit(Bandwidth.builder()
                .capacity(SLOTS_PER_HOUR).refillGreedy(SLOTS_PER_HOUR, Duration.ofHours(1)).build()).build();
    }

    private static final class Entry {
        final Bucket bucket;
        volatile Instant lastUsed = Instant.now();

        Entry(Bucket bucket) {
            this.bucket = bucket;
        }
    }
}
