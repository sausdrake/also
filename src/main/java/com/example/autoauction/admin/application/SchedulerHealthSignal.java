package com.example.autoauction.admin.application;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 73 (G-N4): the smallest possible in-memory operational signal for scheduler
 * success/failure, deliberately not a metrics framework. Every {@code @Scheduled} method in the
 * application may call {@link #reportSuccess(String)}/{@link #reportFailure(String)} exactly
 * once per tick, at the point where its own existing success/failure semantics already resolve -
 * this class never re-interprets a scheduler's own per-candidate isolation logic; it only records
 * the outcome the scheduler itself already decided.
 *
 * <p>State is intentionally in-memory only - no persistence, no migration, no database table.
 * It resets on every application restart; a restart is never itself a "failure" since no state
 * survives to be marked as such. {@link ConcurrentHashMap#compute} provides atomic, thread-safe
 * per-key updates with no explicit locking.
 */
@Component
public class SchedulerHealthSignal {

    /** {@code lastSuccessAt}/{@code lastFailureAt} are {@code null} until first reported. */
    public record SchedulerState(OffsetDateTime lastSuccessAt, OffsetDateTime lastFailureAt, boolean lastExecutionFailed) {
    }

    private final Map<String, SchedulerState> states = new ConcurrentHashMap<>();

    public void reportSuccess(String schedulerId) {
        states.compute(schedulerId, (id, prev) -> new SchedulerState(
                OffsetDateTime.now(),
                prev == null ? null : prev.lastFailureAt(),
                false));
    }

    public void reportFailure(String schedulerId) {
        states.compute(schedulerId, (id, prev) -> new SchedulerState(
                prev == null ? null : prev.lastSuccessAt(),
                OffsetDateTime.now(),
                true));
    }

    /** Read-only, ordered snapshot for the admin status endpoint - never mutated by callers. */
    public Map<String, SchedulerState> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(states));
    }
}
