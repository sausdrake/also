// auction/application/SaleCommitmentResolutionOrchestrator.java
package com.example.autoauction.auction.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.FinalizationOutcome;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * X3-D: resolves the seller commitment financial consequence for an Auction-driven sale
 * outcome (SUCCESS, B, BUYER_FAULT, PLATFORM_FAULT). Outcome C is out of scope - callers
 * must never invoke this class for C (no seller-violation policy exists yet).
 *
 * Single responsibility, narrow on purpose: given a commitment id and a
 * {@link FinalizationOutcome}, load the commitment, verify it is eligible
 * (AWAITING_FINALIZATION), compute the fee/penalty/refund split for that outcome, and finalize
 * it via the existing claim-first {@link SellerDiagnosticCommitmentRepository#finalize} +
 * {@link DepositService#chargeForDiagnosticRequest} pair - the exact same primitives
 * {@link com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentFinalizationService}
 * already uses for the diagnostic-rejection trigger. This class is the Auction-outcome
 * trigger's own path to the same underlying mechanism - it does NOT call, wrap, or modify
 * that service, and does not touch DiagnosticRejectionCategory at all (the Auction-trigger
 * path has no rejection category to check; its own eligibility gate is AWAITING_FINALIZATION
 * only).
 *
 * Commitment identity comes exclusively from the id passed in by the caller - resolved by the
 * caller from {@code Auction.getSellerDiagnosticCommitmentId()}, the immutable, already-
 * resolved snapshot. This class has no dependency capable of reaching Vehicle,
 * DiagnosticRequest, or any other resolution path - by construction, not merely by
 * convention, it cannot re-derive commitment identity any other way.
 *
 * Does NOT: change Auction status, touch Vehicle, create/modify SaleResolution, touch buyer
 * deposit, touch financialResolutionStatus, compute commission/Settlement/seller payout. Each
 * of those remains exactly where it already lives (AuctionFinalizer /
 * SaleFailureResolutionOrchestrator / SaleResolutionService / DepositService's own
 * buyer-deposit methods).
 *
 * Plain {@code @Transactional} (REQUIRED, no REQUIRES_NEW) - joins the caller's own
 * transaction so a failure here rolls back the whole sale-outcome resolution, mirroring
 * {@code unblockForSaleFailure}/{@code chargeForDiagnosticRequest}'s established
 * single-participant reasoning (never a fan-out over many participants).
 */
@Slf4j
@Service
public class SaleCommitmentResolutionOrchestrator {

    private final SellerDiagnosticCommitmentRepository commitmentRepository;
    private final DepositService depositService;

    public SaleCommitmentResolutionOrchestrator(SellerDiagnosticCommitmentRepository commitmentRepository,
                                                 DepositService depositService) {
        this.commitmentRepository = commitmentRepository;
        this.depositService = depositService;
    }

    /**
     * Resolves the commitment identified by {@code sellerDiagnosticCommitmentId} as
     * {@code outcome}. The caller must have already verified {@code sellerDiagnosticCommitmentId}
     * is non-null (a historical, commitment-less auction must never reach this method at all -
     * see the caller's own null check).
     *
     * Idempotency: if the commitment is already FINALIZED with this same outcome, this is a
     * safe no-op (no second ledger mutation). If it is FINALIZED with a DIFFERENT outcome, or
     * in any other non-AWAITING_FINALIZATION status, this fails closed with
     * IllegalStateException and mutates nothing.
     */
    @Transactional
    public void resolve(Long sellerDiagnosticCommitmentId, FinalizationOutcome outcome) {
        SellerDiagnosticCommitment commitment = commitmentRepository.findById(sellerDiagnosticCommitmentId)
                .orElseThrow(() -> new IllegalStateException(
                        "SellerDiagnosticCommitment " + sellerDiagnosticCommitmentId + " не найден"));

        if (commitment.getStatus() != CommitmentStatus.AWAITING_FINALIZATION) {
            if (commitment.getStatus() == CommitmentStatus.FINALIZED
                    && commitment.getFinalizationOutcome() == outcome) {
                log.info("Commitment {} уже FINALIZED как {} - идемпотентный no-op",
                        sellerDiagnosticCommitmentId, outcome);
                return;
            }
            throw new IllegalStateException(String.format(
                    "Commitment %d не готов к финализации как %s: текущий статус '%s'%s",
                    sellerDiagnosticCommitmentId, outcome, commitment.getStatus(),
                    commitment.getFinalizationOutcome() != null ? " (outcome=" + commitment.getFinalizationOutcome() + ")" : ""));
        }

        BigDecimal committedAmount = commitment.getCommittedAmount();
        // X3-D: A always means zero fee/zero penalty/full refund, regardless of which of the
        // three Auction-level triggers (SUCCESS/BUYER_FAULT/PLATFORM_FAULT) produced it - see
        // FinalizationOutcome.A's own Javadoc for why those three do not get distinct values.
        BigDecimal feeAmount = (outcome == FinalizationOutcome.B)
                ? commitment.getDiagnosticFeeAmount()
                : BigDecimal.ZERO;

        // X3-D corrective: mirrors SellerDiagnosticCommitmentFinalizationService.finalizeAsOutcomeB's
        // own guard exactly (same invariant, same exception type, same position - before any
        // refund/finalizedAt computation, before the claim, before any ledger mutation). Not
        // merely "insurance against a hypothetical future regression": committedAmount and
        // diagnosticFeeAmount are two independently admin-configured SystemSettings values with
        // no cross-field validation at creation time, so this is a real, currently-reachable
        // misconfiguration this orchestrator must not silently propagate into a negative refund.
        if (feeAmount.compareTo(committedAmount) > 0) {
            throw new IllegalStateException(
                    "Commitment " + sellerDiagnosticCommitmentId + " has diagnosticFeeAmount (" + feeAmount +
                            ") exceeding committedAmount (" + committedAmount + ") - refusing to finalize");
        }

        BigDecimal penaltyAmount = BigDecimal.ZERO;
        BigDecimal refundAmount = committedAmount.subtract(feeAmount);
        OffsetDateTime finalizedAt = OffsetDateTime.now();

        // Claim FIRST: nothing financial happens unless this call's own UPDATE actually
        // matched the row - identical discipline to
        // SellerDiagnosticCommitmentFinalizationService.finalizeAsOutcomeB.
        boolean claimed = commitmentRepository.finalize(commitment.getDiagnosticRequestId(), outcome,
                penaltyAmount, refundAmount, finalizedAt);

        if (!claimed) {
            SellerDiagnosticCommitment current = commitmentRepository.findById(sellerDiagnosticCommitmentId)
                    .orElseThrow(() -> new IllegalStateException(
                            "SellerDiagnosticCommitment " + sellerDiagnosticCommitmentId + " не найден"));

            if (current.getStatus() == CommitmentStatus.FINALIZED && current.getFinalizationOutcome() == outcome) {
                log.info("Commitment {} уже FINALIZED как {} (проиграна гонка claim) - идемпотентный no-op",
                        sellerDiagnosticCommitmentId, outcome);
                return;
            }

            throw new IllegalStateException(String.format(
                    "Не удалось финализировать commitment %d как %s - неожиданное состояние: '%s'%s",
                    sellerDiagnosticCommitmentId, outcome, current.getStatus(),
                    current.getFinalizationOutcome() != null ? " (outcome=" + current.getFinalizationOutcome() + ")" : ""));
        }

        // Claim won - the commitment row is now durably FINALIZED within THIS transaction.
        // The ledger mutation shares this same transaction (REQUIRED, no REQUIRES_NEW): if it
        // throws, the whole transaction - including the claim just performed - rolls back.
        depositService.chargeForDiagnosticRequest(commitment.getDepositId(), commitment.getDiagnosticRequestId(),
                committedAmount, feeAmount);

        log.info("Commitment {} finalized as {}: fee={}, penalty={}, refund={}",
                sellerDiagnosticCommitmentId, outcome, feeAmount, penaltyAmount, refundAmount);
    }
}
