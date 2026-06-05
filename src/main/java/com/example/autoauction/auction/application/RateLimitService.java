package com.example.autoauction.auction.application;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class RateLimitService {

    static final int BIDS_PER_MINUTE = 10;

    // Bucket + timestamp последнего использования для eviction
    private final Map<Long, BucketEntry> userBuckets = new ConcurrentHashMap<>();

    public boolean tryAcquire(Long userId) {
        BucketEntry entry = userBuckets.compute(userId, (id, existing) -> {
            if (existing == null) return new BucketEntry(createBucket());
            existing.touch();
            return existing;
        });
        return entry.bucket.tryConsume(1);
    }

    public void resetUserLimit(Long userId) {
        userBuckets.remove(userId);
        log.debug("Rate limit reset for user: {}", userId);
    }

    /** Каждые 10 минут удаляем бакеты, которые не использовались более 15 минут. */
    @Scheduled(fixedDelay = 10 * 60_000)
    public void evictStaleBuckets() {
        Instant threshold = Instant.now().minus(Duration.ofMinutes(15));
        int before = userBuckets.size();
        userBuckets.entrySet().removeIf(e -> e.getValue().lastUsed.isBefore(threshold));
        int removed = before - userBuckets.size();
        if (removed > 0) {
            log.debug("RateLimitService: evicted {} stale buckets, remaining: {}", removed, userBuckets.size());
        }
    }

    int bucketCount() {
        return userBuckets.size();
    }

    private Bucket createBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(BIDS_PER_MINUTE)
                .refillGreedy(BIDS_PER_MINUTE, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    static class BucketEntry {
        final Bucket bucket;
        volatile Instant lastUsed;

        BucketEntry(Bucket bucket) {
            this.bucket = bucket;
            this.lastUsed = Instant.now();
        }

        void touch() {
            lastUsed = Instant.now();
        }
    }
}
