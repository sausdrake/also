// common/application/FinancialRecoveryService.java
package com.example.autoauction.common.application;

import com.example.autoauction.common.domain.FailedOperation;
import com.example.autoauction.common.domain.port.FailedOperationRepository;
import com.example.autoauction.deposit.application.DepositService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * R-2: reconciles FailedOperation records left behind by the REQUIRES_NEW-isolated
 * financial operations (DepositService.unfreezeForAuction / markAsWinner /
 * unfreezeForDiagnosticRequest). Those methods swallow their own failures internally and
 * never throw to their caller (AuctionFinalizer's *Safely wrappers), so this service
 * never trusts their return signal - it independently verifies the underlying financial
 * state before AND after attempting a retry, exactly as required by "perform the
 * operation only if still required" / "verify resulting state".
 *
 * State machine: PENDING -> PROCESSING (atomic claim) -> RESOLVED (verified done), or
 * PROCESSING -> PENDING (retry later, backoff) / FAILED (escalated after max attempts,
 * never auto-retried again, but never deleted - stays visible).
 */
@Slf4j
@Service
public class FinancialRecoveryService {

    static final int MAX_RETRY_ATTEMPTS = 5;

    /** Index = retryCount at the time of THIS failure (0-based). Last entry -> escalate to FAILED instead. */
    static final Duration[] BACKOFF = {
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofHours(1),
    };

    private final FailedOperationRepository failedOperationRepository;
    private final DepositService depositService;
    private final Clock clock;

    public FinancialRecoveryService(FailedOperationRepository failedOperationRepository,
                                    DepositService depositService,
                                    Clock clock) {
        this.failedOperationRepository = failedOperationRepository;
        this.depositService = depositService;
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /** Called by the scheduler. Finds and processes every currently-retryable PENDING operation. */
    public void recoverPending() {
        List<FailedOperation> candidates = failedOperationRepository.findRetryablePending(now());
        if (candidates.isEmpty()) return;

        log.info("FinancialRecoveryService: {} failed operation(s) eligible for retry", candidates.size());
        for (FailedOperation candidate : candidates) {
            try {
                recoverOne(candidate.getId());
            } catch (Exception e) {
                // recoverOne already handles its own failure bookkeeping internally; this
                // is a last-resort guard so one bad record can never stop the batch.
                log.error("Unexpected error processing failed operation {}: {}", candidate.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Processes exactly one FailedOperation, if this call wins the atomic claim.
     * @Transactional (REQUIRED, its own top-level boundary): if the process crashes
     * between the claim and the final resolve/retry-marking, this whole transaction rolls
     * back, reverting the claim to PENDING automatically - safe because the underlying
     * retry (unfreezeForAuction/markAsWinner) runs in ITS OWN REQUIRES_NEW transaction and
     * would already be durably committed if it actually succeeded, so a reverted claim
     * just means the NEXT attempt re-verifies state, finds it already correct, and
     * resolves without a second mutation.
     */
    @Transactional
    public void recoverOne(Long failedOperationId) {
        if (!failedOperationRepository.claim(failedOperationId)) {
            log.debug("Failed operation {} already claimed by another worker or no longer PENDING, skipping", failedOperationId);
            return;
        }

        FailedOperation op = failedOperationRepository.findById(failedOperationId)
                .orElseThrow(() -> new IllegalStateException("Claimed failed operation not found: " + failedOperationId));

        try {
            boolean stillRequired = isStillRequired(op);
            if (stillRequired) {
                performRetry(op);
                stillRequired = isStillRequired(op);
            }

            if (stillRequired) {
                // The retry ran but verification shows the state is STILL not correct -
                // the underlying operation silently failed again (it never throws).
                throw new IllegalStateException(
                        "Retry of " + op.getOperationType() + " did not resolve the required financial state");
            }

            markResolved(op);
        } catch (Exception e) {
            markRetryOrEscalate(op, e);
        }
    }

    /**
     * True if the target financial state still needs this operation applied. Delegates to
     * DepositService's own REQUIRES_NEW read methods rather than reading via
     * depositRepository directly here - a private method on THIS class cannot get its own
     * transaction via self-invocation (Spring's proxy-based @Transactional does not
     * intercept this.method() calls within the same bean), so the fresh-read guarantee
     * must come from a genuinely different bean.
     * @throws IllegalStateException if the target deposit cannot be found at all - this is
     *         a genuine unresolved problem, not success, so it must flow into the normal
     *         retry/escalate path (and eventually FAILED, visible to an admin) rather than
     *         being silently treated as "nothing to do".
     */
    private boolean isStillRequired(FailedOperation op) {
        return switch (op.getOperationType()) {
            case "UNFREEZE_AUCTION", "UNFREEZE_DIAGNOSTIC_REQUEST" ->
                    depositService.isStillFrozenForAuction(op.getUserId(), op.getAuctionId());
            case "MARK_WINNER" -> depositService.needsWinnerMark(op.getUserId(), op.getAuctionId());
            default -> {
                log.warn("Unknown operation type '{}' for failed operation {}, cannot verify or retry",
                        op.getOperationType(), op.getId());
                yield false;
            }
        };
    }

    private void performRetry(FailedOperation op) {
        switch (op.getOperationType()) {
            case "UNFREEZE_AUCTION" -> depositService.unfreezeForAuction(op.getUserId(), op.getAuctionId());
            case "UNFREEZE_DIAGNOSTIC_REQUEST" -> depositService.unfreezeForDiagnosticRequest(op.getUserId(), op.getAuctionId());
            case "MARK_WINNER" -> depositService.markAsWinner(op.getUserId(), op.getAuctionId());
            default -> throw new IllegalStateException("Unknown operation type: " + op.getOperationType());
        }
    }

    private void markResolved(FailedOperation op) {
        op.setStatus(FailedOperation.STATUS_RESOLVED);
        op.setResolvedAt(now());
        op.setUpdatedAt(now());
        op.setNextRetryAt(null);
        failedOperationRepository.save(op);
        log.info("Failed operation {} ({}, user={}, auction={}) resolved by recovery",
                op.getId(), op.getOperationType(), op.getUserId(), op.getAuctionId());
    }

    private void markRetryOrEscalate(FailedOperation op, Exception e) {
        int attempt = op.getRetryCount();
        op.setRetryCount(attempt + 1);
        op.setErrorMessage(e.getMessage());
        op.setUpdatedAt(now());

        if (attempt >= MAX_RETRY_ATTEMPTS) {
            op.setStatus(FailedOperation.STATUS_FAILED);
            op.setNextRetryAt(null);
            failedOperationRepository.save(op);
            log.error("CRITICAL: Failed operation {} ({}, user={}, auction={}) escalated to FAILED " +
                            "after {} attempts - requires manual/admin attention. Last error: {}",
                    op.getId(), op.getOperationType(), op.getUserId(), op.getAuctionId(), attempt + 1, e.getMessage());
            return;
        }

        Duration backoff = BACKOFF[Math.min(attempt, BACKOFF.length - 1)];
        op.setStatus(FailedOperation.STATUS_PENDING);
        op.setNextRetryAt(now().plus(backoff));
        failedOperationRepository.save(op);
        log.warn("Failed operation {} ({}, user={}, auction={}) retry {} failed, will retry after {}: {}",
                op.getId(), op.getOperationType(), op.getUserId(), op.getAuctionId(), attempt + 1, backoff, e.getMessage());
    }
}
