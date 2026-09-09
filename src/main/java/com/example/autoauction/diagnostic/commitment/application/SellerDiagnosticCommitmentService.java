// diagnostic/commitment/application/SellerDiagnosticCommitmentService.java
package com.example.autoauction.diagnostic.commitment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.policy.application.PolicyVersionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * PHASE 1 (seller commitment redesign): owns the referentially-correct persistence of a
 * seller's diagnostic-request commitment, replacing the previous FrozenDeposit-based
 * storage (which stored a diagnostic-request id in deposit_frozen.auction_id, a column
 * FK'd to auctions(id) - see Phase 0 / F-0.1 audit findings). Orchestrates between
 * DiagnosticRequest, this commitment record, and the Deposit ledger via DepositService -
 * never mutates Deposit balance/blockedAmount directly, never touches JPA/infrastructure
 * directly.
 *
 * Phase 1 scope only: no fee/penalty/refund/finalization-outcome logic. A commitment is
 * either fully frozen or fully released, exactly matching the diagnostic module's current
 * behavior - only the persistence path changes. The auction-side FrozenDeposit model is
 * untouched and remains a separate financial concept.
 */
@Slf4j
@Service
public class SellerDiagnosticCommitmentService {

    private final SellerDiagnosticCommitmentRepository repository;
    private final DepositService depositService;
    private final PolicyVersionService policyVersionService;

    public SellerDiagnosticCommitmentService(SellerDiagnosticCommitmentRepository repository,
                                             DepositService depositService,
                                             PolicyVersionService policyVersionService) {
        this.repository = repository;
        this.depositService = depositService;
        this.policyVersionService = policyVersionService;
    }

    /**
     * Freezes the configured commitment amount for a newly-created diagnostic request and
     * records the correctly-typed commitment, snapshotting both committedAmount and
     * diagnosticFeeAmount (PHASE 2) - neither is ever re-read from configuration after
     * this call. Shares the caller's transaction (no REQUIRES_NEW) - a freeze failure must
     * abort the whole diagnostic-request creation, matching current behavior exactly
     * (DiagnosticRequestService.createRequest has no try/catch around this call).
     *
     * X3-E: also snapshots the currently published PolicyVersion's id onto the commitment -
     * the diagnostic/commitment-stage policy reference used by the diagnostic-rejection
     * SELLER_VIOLATION trigger (deliberately independent from Auction.policyVersionId, its
     * own separately-snapshotted-at-auction-creation-time reference - locked X3-E business
     * decision, the two are allowed to diverge). Fails closed (propagates
     * PolicyVersionService.getCurrentPublished()'s IllegalStateException, aborting the whole
     * freeze/diagnostic-request creation) if no PolicyVersion has ever been published -
     * mirrors AdminAuctionService's own existing fail-closed convention for
     * Auction.policyVersionId (V33).
     */
    @Transactional
    public void freezeCommitment(Long sellerId, Long diagnosticRequestId,
                                  BigDecimal committedAmount, BigDecimal diagnosticFeeAmount) {
        if (repository.existsByDiagnosticRequestId(diagnosticRequestId)) {
            throw new IllegalStateException(
                    "Seller diagnostic commitment already exists for request " + diagnosticRequestId);
        }

        Long depositId = depositService.getDepositByUserId(sellerId).id();
        Long policyVersionId = policyVersionService.getCurrentPublished().getId();

        // Mutates the Deposit ledger (balance/blockedAmount) and records the
        // DepositTransaction - DepositService remains solely responsible for that; this
        // service never touches Deposit internals directly. Only committedAmount is a
        // ledger concern - diagnosticFeeAmount is a commitment-only snapshot, never passed
        // to DepositService.
        depositService.freezeForDiagnosticRequest(sellerId, diagnosticRequestId, committedAmount);

        SellerDiagnosticCommitment commitment = new SellerDiagnosticCommitment();
        commitment.setDiagnosticRequestId(diagnosticRequestId);
        commitment.setDepositId(depositId);
        commitment.setCommittedAmount(committedAmount);
        commitment.setDiagnosticFeeAmount(diagnosticFeeAmount);
        commitment.setStatus(CommitmentStatus.FROZEN);
        commitment.setFrozenAt(OffsetDateTime.now());
        commitment.setPolicyVersionId(policyVersionId);
        repository.save(commitment);

        log.info("Seller diagnostic commitment frozen: request={}, seller={}, committedAmount={}, diagnosticFeeAmount={}, policyVersionId={}",
                diagnosticRequestId, sellerId, committedAmount, diagnosticFeeAmount, policyVersionId);
    }

    /**
     * PHASE 2: transitions a commitment from FROZEN to AWAITING_FINALIZATION once the
     * diagnostic lifecycle has concluded (report approved, or rejected with a recorded
     * category) - marks that the financial outcome is now known-pending but NOT yet
     * computed. Does NOT touch the Deposit ledger, does NOT release/delete the commitment,
     * does NOT compute any fee/penalty/refund - that is exclusively a future finalization
     * phase's responsibility. Plain @Transactional (REQUIRED, no REQUIRES_NEW): this
     * mutates only the commitment table, never Deposit, so none of the Hibernate-session-
     * poisoning concerns that motivate REQUIRES_NEW elsewhere in this codebase apply here;
     * it deliberately shares the caller's transaction (approveReport/rejectReport) so a
     * failure here rolls back the whole operation, exactly like the releaseCommitment call
     * it replaces at those call sites.
     *
     * Race handling mirrors DiagnosticRequestService.bindTemplate's established precedent:
     * a 0-rows-affected UPDATE is never silently treated as success - it is re-read and
     * distinguished. Deliberately does NOT attempt to arbitrate DiagnosticRequest's own
     * separate (pre-existing, out-of-scope) approve/reject race - it only ever asks "is
     * THIS commitment now AWAITING_FINALIZATION," treating an already-transitioned
     * commitment as an idempotent outcome, not a conflict.
     */
    @Transactional
    public void markAwaitingFinalization(Long diagnosticRequestId) {
        boolean transitioned = repository.markAwaitingFinalization(diagnosticRequestId);
        if (transitioned) {
            log.info("Seller diagnostic commitment for request {} transitioned FROZEN -> AWAITING_FINALIZATION",
                    diagnosticRequestId);
            return;
        }

        SellerDiagnosticCommitment current = repository.findByDiagnosticRequestId(diagnosticRequestId)
                .orElseThrow(() -> new IllegalStateException(
                        "No seller diagnostic commitment found for request " + diagnosticRequestId));

        if (current.getStatus() == CommitmentStatus.AWAITING_FINALIZATION) {
            log.info("Seller diagnostic commitment for request {} already AWAITING_FINALIZATION - idempotent",
                    diagnosticRequestId);
            return;
        }

        // Only FROZEN/AWAITING_FINALIZATION exist in Phase 2 - unreachable in practice,
        // fails closed defensively rather than silently succeeding on an unexpected status.
        throw new IllegalStateException(
                "Could not transition seller diagnostic commitment for request " + diagnosticRequestId
                        + " to AWAITING_FINALIZATION - unexpected status: " + current.getStatus());
    }

    /**
     * Releases (fully, Phase 1 scope) a seller's diagnostic commitment. A missing
     * commitment is a safe no-op (mirrors DepositService.unfreezeForDiagnosticRequest's
     * previous "no frozen amount found" behavior - e.g. a request that was never
     * successfully frozen). DepositService.unfreezeForDiagnosticRequest already records
     * failures via FailedOperationService and re-throws; this method deliberately does not
     * wrap that call in a second try/catch, preserving the exact current propagation
     * semantics (a failure here still rolls back the caller's transaction, e.g.
     * approveReport/rejectReport).
     */
    @Transactional
    public void releaseCommitment(Long sellerId, Long diagnosticRequestId) {
        Optional<SellerDiagnosticCommitment> commitment = repository.findByDiagnosticRequestId(diagnosticRequestId);
        if (commitment.isEmpty()) {
            log.warn("No seller diagnostic commitment found for request {}, skipping release", diagnosticRequestId);
            return;
        }

        depositService.unfreezeForDiagnosticRequest(sellerId, diagnosticRequestId, commitment.get().getCommittedAmount());

        repository.deleteByDiagnosticRequestId(diagnosticRequestId);
        log.info("Seller diagnostic commitment released: request={}, seller={}", diagnosticRequestId, sellerId);
    }

    /**
     * Used by FinancialRecoveryService to verify whether an UNFREEZE_DIAGNOSTIC_REQUEST
     * retry is still needed. Exists-style query only - never hydrates a managed entity -
     * matching DepositService.needsWinnerMark's established immunity to Hibernate
     * first-level-cache staleness across a REQUIRES_NEW retry (see that method's Javadoc).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean hasActiveCommitment(Long diagnosticRequestId) {
        return repository.existsByDiagnosticRequestId(diagnosticRequestId);
    }
}
