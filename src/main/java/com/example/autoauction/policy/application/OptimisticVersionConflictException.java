package com.example.autoauction.policy.application;

/**
 * Locked concurrency design, Boundary A: the caller's observed {@code version} for a
 * PolicyVersion or PolicyVersionViolation row no longer matches the row's real persisted
 * version - someone else already changed exactly this row since the caller last read it. Thrown
 * as an explicit, up-front check (see {@code PolicyVersionService}) BEFORE attempting the
 * mutation, which is necessary in addition to Hibernate's own {@code @Version}-checked flush:
 * that flush-time mechanism alone only catches a genuine race between two requests that both
 * loaded the SAME not-yet-stale version; it cannot catch a caller whose observed version was
 * already known-stale before this request even started; this explicit check closes that gap
 * (the check still races openly with a concurrent writer between this check and the save, which
 * is exactly what Hibernate's own {@code @Version} flush-time check then catches for that
 * narrower window - see {@code org.springframework.orm.ObjectOptimisticLockingFailureException},
 * handled separately in {@code GlobalExceptionHandler} with the same {@code code}).
 */
public class OptimisticVersionConflictException extends PolicyConflictException {

    public OptimisticVersionConflictException(String entityDescription, Long observedVersion, Long currentVersion) {
        super("OPTIMISTIC_VERSION_CONFLICT",
                entityDescription + " было изменено другим администратором (наблюдалась версия "
                        + observedVersion + ", текущая версия - " + currentVersion
                        + "). Обновите данные и попробуйте снова.");
    }
}
