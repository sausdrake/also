package com.example.autoauction.diagnostic.commitment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.FinalizationOutcome;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRejectionCategory;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * PHASE 3B: computes and applies the Outcome B financial finalization for a seller
 * diagnostic commitment - the ONLY outcome implemented in this phase. Owns the
 * AWAITING_FINALIZATION -&gt; FINALIZED transition and the ledger mutation together, as one
 * atomic unit (plain @Transactional, REQUIRED - deliberately NOT REQUIRES_NEW: this touches
 * exactly one commitment and one deposit, never a fan-out over multiple participants, so
 * none of the REQUIRES_NEW-plus-swallow reasoning used elsewhere in this codebase for
 * multi-participant operations applies here). A ledger-mutation failure must roll back the
 * whole finalization, including the just-claimed FINALIZED status - never leaving a
 * "finalized but unpaid" state (see the atomic claim's own Javadoc for the concurrency
 * proof this depends on).
 *
 * Deliberately does NOT read SystemSettingsService - both committedAmount and
 * diagnosticFeeAmount come exclusively from the commitment's own immutable snapshot,
 * exactly as the business-rule freeze requires.
 *
 * Deliberately does NOT compute commission, does NOT touch Settlement/buyer payment/auction
 * SOLD status, does NOT implement Outcome A or C, does NOT calculate any penalty - all
 * remain out of scope for this phase.
 *
 * Trigger timing: per the Phase 3 architecture decision lock, this must run in a SEPARATE
 * transaction from whatever admin action (rejectReport) brought the commitment to
 * AWAITING_FINALIZATION, so that a ledger failure here can never roll back an already-valid
 * admin decision. Since Spring's proxy-based @Transactional only creates a genuinely new
 * transaction when there is no active one to join, and self-invocation from within another
 * @Transactional method on the SAME bean silently bypasses the proxy entirely (a
 * well-known Spring AOP limitation), this service must be invoked from a caller that is
 * itself NOT already inside a transaction - see AdminDiagnosticController.rejectReport,
 * which calls DiagnosticRequestService.rejectReport() (its own complete transaction) and
 * only then, as a separate subsequent call, this method.
 *
 * PHASE 3B corrective slice: has two callers now - rejectReport (best-effort, exception
 * caught and logged there so a finalization failure never undoes the already-committed
 * rejection) and AdminDiagnosticController's manual retry endpoint (no try/catch there -
 * exceptions propagate to GlobalExceptionHandler, exactly like the existing
 * /media/{id}/retry-processing precedent), for recovering a commitment left durably
 * AWAITING_FINALIZATION by a transient failure in the first call. No scheduler, no
 * automatic retry, no FinancialRecoveryService integration - recovery is manual-admin-
 * triggered only, deliberately, for this phase.
 */
@Slf4j
@Service
public class SellerDiagnosticCommitmentFinalizationService {

    private final SellerDiagnosticCommitmentRepository repository;
    private final DepositService depositService;
    private final DiagnosticRequestRepository diagnosticRequestRepository;

    public SellerDiagnosticCommitmentFinalizationService(SellerDiagnosticCommitmentRepository repository,
                                                          DepositService depositService,
                                                          DiagnosticRequestRepository diagnosticRequestRepository) {
        this.repository = repository;
        this.depositService = depositService;
        this.diagnosticRequestRepository = diagnosticRequestRepository;
    }

    /**
     * Finalizes a commitment as Outcome B (fee charged, zero penalty, remainder refunded).
     *
     * PHASE 3B corrective slice: now has two callers - AdminDiagnosticController.rejectReport
     * (which already only calls this for VEHICLE_CONDITION) AND the new manual admin retry
     * endpoint (which may be called for an arbitrary requestId without the caller having
     * re-checked its category). Trusting the caller was safe with one call site; with two,
     * it is not - so eligibility is now verified HERE, unconditionally, as the very first
     * step, reading the triggering DiagnosticRequest's rejectionCategory via the (read-only)
     * DiagnosticRequestRepository port. This is the smallest correct place for the guard:
     * it makes the method safe regardless of which caller invokes it, rather than relying on
     * every current and future caller to remember the precondition themselves.
     *
     * Idempotent: if the commitment is already FINALIZED, this is a safe no-op. If the
     * DiagnosticRequest is not found, fails with IllegalArgumentException (matches
     * DiagnosticRequestService's own "not found" convention). If it is found but not
     * VEHICLE_CONDITION, or the commitment is not found, or is in an unexpected
     * non-AWAITING_FINALIZATION state, fails closed with IllegalStateException.
     */
    @Transactional
    public void finalizeAsOutcomeB(Long diagnosticRequestId) {
        DiagnosticRequest request = diagnosticRequestRepository.findById(diagnosticRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + diagnosticRequestId));

        if (request.getRejectionCategory() != DiagnosticRejectionCategory.VEHICLE_CONDITION) {
            throw new IllegalStateException(
                    "Request " + diagnosticRequestId + " is not eligible for Outcome B finalization - " +
                            "rejectionCategory is " + request.getRejectionCategory());
        }

        SellerDiagnosticCommitment commitment = repository.findByDiagnosticRequestId(diagnosticRequestId)
                .orElseThrow(() -> new IllegalStateException(
                        "No seller diagnostic commitment found for request " + diagnosticRequestId));

        BigDecimal committedAmount = commitment.getCommittedAmount();
        BigDecimal feeAmount = commitment.getDiagnosticFeeAmount();

        // Defensive (finalization-layer) validation: creation-time validation already
        // prevents diagnosticFeeAmount > committedAmount, and both snapshots are immutable
        // after creation, so this should never actually trigger in normal operation - kept
        // as fail-closed insurance against a future regression elsewhere, not because it is
        // currently reachable through any known code path.
        if (feeAmount.compareTo(committedAmount) > 0) {
            throw new IllegalStateException(
                    "Commitment for request " + diagnosticRequestId + " has diagnosticFeeAmount (" + feeAmount +
                            ") exceeding committedAmount (" + committedAmount + ") - refusing to finalize");
        }

        BigDecimal penaltyAmount = BigDecimal.ZERO;
        BigDecimal refundAmount = committedAmount.subtract(feeAmount);
        OffsetDateTime finalizedAt = OffsetDateTime.now();

        // Atomic claim FIRST: nothing financial happens unless this call's own UPDATE
        // actually matched the row. A losing claimant (0 rows) must never touch the ledger -
        // this ordering is the entire basis of the exact-once guarantee.
        boolean claimed = repository.finalize(diagnosticRequestId, FinalizationOutcome.B,
                penaltyAmount, refundAmount, finalizedAt);

        if (!claimed) {
            SellerDiagnosticCommitment current = repository.findByDiagnosticRequestId(diagnosticRequestId)
                    .orElseThrow(() -> new IllegalStateException(
                            "No seller diagnostic commitment found for request " + diagnosticRequestId));

            if (current.getStatus() == CommitmentStatus.FINALIZED) {
                log.info("Seller diagnostic commitment for request {} already FINALIZED - idempotent",
                        diagnosticRequestId);
                return;
            }

            throw new IllegalStateException(
                    "Could not finalize seller diagnostic commitment for request " + diagnosticRequestId +
                            " as Outcome B - unexpected status: " + current.getStatus());
        }

        // Claim won - the commitment row is now durably FINALIZED within THIS transaction.
        // The ledger mutation below shares this same transaction (REQUIRED, no
        // REQUIRES_NEW): if it throws, the whole transaction - including the claim just
        // performed - rolls back together, so a ledger failure can never leave a
        // FINALIZED-without-ledger state.
        depositService.chargeForDiagnosticRequest(commitment.getDepositId(), diagnosticRequestId,
                committedAmount, feeAmount);

        log.info("Seller diagnostic commitment for request {} finalized as Outcome B: fee={}, penalty={}, refund={}",
                diagnosticRequestId, feeAmount, penaltyAmount, refundAmount);
    }
}
