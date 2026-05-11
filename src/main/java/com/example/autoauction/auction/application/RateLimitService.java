package com.example.autoauction.auction.application;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.Refill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class RateLimitService {

    // 10 ставок в минуту на пользователя
    private static final int BIDS_PER_MINUTE = 10;
    private final Map<Long, Bucket> userBuckets = new ConcurrentHashMap<>();

    public boolean tryAcquire(Long userId) {
        Bucket bucket = userBuckets.computeIfAbsent(userId, this::createBucket);
        return bucket.tryConsume(1);
    }

    private Bucket createBucket(Long userId) {
        Bandwidth limit = Bandwidth.classic(BIDS_PER_MINUTE, Refill.greedy(BIDS_PER_MINUTE, Duration.ofMinutes(1)));
        return Bucket4j.builder().addLimit(limit).build();
    }

    public void resetUserLimit(Long userId) {
        userBuckets.remove(userId);
        log.debug("Rate limit reset for user: {}", userId);
    }
}