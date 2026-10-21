package com.example.autoauction.auth.application;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 64: IP-keyed rate limiting for pre-authentication endpoints (login, registration, the
 * password-hash utility). Deliberately separate from {@link
 * com.example.autoauction.auction.application.RateLimitService}, which is keyed by an
 * authenticated user's id and therefore cannot apply before authentication succeeds - no such id
 * exists yet at login/register time. Mirrors that service's proven bucket/eviction lifecycle
 * exactly; only the key (scope+IP, never userId or username/email) and per-scope capacity differ.
 *
 * <p>IP-only keying is deliberate: it can never lock a legitimate account out of the system
 * (worst case, one IP is temporarily throttled - the account owner can always retry from
 * elsewhere), unlike a username/email-keyed scheme, which would let an attacker who merely knows
 * a victim's username lock that victim out from anywhere. The client IP is read from {@code
 * HttpServletRequest.getRemoteAddr()} by the caller ({@code AuthController}) - never from {@code
 * X-Forwarded-For} or any other client-suppliable header, since no reverse proxy exists in this
 * deployment to validate such a header; once one is introduced, IP extraction must be reviewed.
 *
 * <p>Each {@link Scope} has its own independent bucket per IP (key = {@code scope + ":" + ip}) -
 * exhausting one auth endpoint's limit from a given IP never throttles a different endpoint from
 * that same IP.
 */
@Slf4j
@Service
public class AuthRateLimitService {

    static final int LOGIN_PER_MINUTE = 5;
    static final int REGISTER_PER_MINUTE = 3;
    /** AUTH-TELEGRAM-01: Telegram sign-in start + callback, per IP (one sign-in = 2 requests). */
    static final int TELEGRAM_PER_MINUTE = 10;

    public enum Scope { LOGIN, REGISTER, TELEGRAM }

    // Bucket + timestamp последнего использования для eviction
    private final Map<String, BucketEntry> buckets = new ConcurrentHashMap<>();

    public boolean tryAcquire(Scope scope, String clientIp) {
        String key = key(scope, clientIp);
        BucketEntry entry = buckets.compute(key, (k, existing) -> {
            if (existing == null) return new BucketEntry(createBucket(scope));
            existing.touch();
            return existing;
        });
        return entry.bucket.tryConsume(1);
    }

    public void resetLimit(Scope scope, String clientIp) {
        buckets.remove(key(scope, clientIp));
        log.debug("Auth rate limit reset for scope={} ip={}", scope, clientIp);
    }

    /** Каждые 10 минут удаляем бакеты, которые не использовались более 15 минут. */
    @Scheduled(fixedDelay = 10 * 60_000)
    public void evictStaleBuckets() {
        Instant threshold = Instant.now().minus(Duration.ofMinutes(15));
        int before = buckets.size();
        buckets.entrySet().removeIf(e -> e.getValue().lastUsed.isBefore(threshold));
        int removed = before - buckets.size();
        if (removed > 0) {
            log.debug("AuthRateLimitService: evicted {} stale buckets, remaining: {}", removed, buckets.size());
        }
    }

    int bucketCount() {
        return buckets.size();
    }

    private static String key(Scope scope, String clientIp) {
        return scope.name() + ":" + clientIp;
    }

    private Bucket createBucket(Scope scope) {
        int capacity = switch (scope) {
            case LOGIN -> LOGIN_PER_MINUTE;
            case REGISTER -> REGISTER_PER_MINUTE;
            case TELEGRAM -> TELEGRAM_PER_MINUTE;
        };
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, Duration.ofMinutes(1))
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
