package com.example.autoauction.diagnostic.commitment.domain.port;

import com.example.autoauction.diagnostic.commitment.domain.FinalizationOutcome;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

public interface SellerDiagnosticCommitmentRepository {

    SellerDiagnosticCommitment save(SellerDiagnosticCommitment commitment);

    /**
     * X3-D: the narrow, additive lookup needed to traverse Auction.sellerDiagnosticCommitmentId
     * -> SellerDiagnosticCommitment directly by the commitment's own id, without going back
     * through diagnosticRequestId (the only key every pre-X3-D method used). Added specifically
     * so commitment resolution driven by an Auction outcome never needs to re-resolve through
     * Vehicle/DiagnosticRequest to find the commitment it already has the id for.
     */
    Optional<SellerDiagnosticCommitment> findById(Long id);

    Optional<SellerDiagnosticCommitment> findByDiagnosticRequestId(Long diagnosticRequestId);

    boolean existsByDiagnosticRequestId(Long diagnosticRequestId);

    void deleteByDiagnosticRequestId(Long diagnosticRequestId);

    /**
     * PHASE 2: atomic FROZEN -&gt; AWAITING_FINALIZATION transition, equivalent to
     * {@code UPDATE seller_diagnostic_commitment SET status = 'AWAITING_FINALIZATION', ...
     * WHERE diagnostic_request_id = ? AND status = 'FROZEN'}. Returns true only if this
     * call's own UPDATE actually matched a row - the sole concurrency guard, mirroring
     * every other atomic state-transition port method in this codebase (e.g.
     * DiagnosticRequestRepository.bindTemplate, DepositRepository.claimForProcessing-style
     * guards). No SELECT-then-mutate-then-save path exists for this transition.
     */
    boolean markAwaitingFinalization(Long diagnosticRequestId);

    /**
     * PHASE 3B: atomic AWAITING_FINALIZATION -&gt; FINALIZED transition, equivalent to
     * {@code UPDATE seller_diagnostic_commitment SET status = 'FINALIZED', finalization_outcome
     * = ?, finalized_at = ?, penalty_amount = ?, refund_amount = ? WHERE diagnostic_request_id
     * = ? AND status = 'AWAITING_FINALIZATION'}. Returns true only if this call's own UPDATE
     * actually matched a row - the sole concurrency guard, identical in shape to
     * {@link #markAwaitingFinalization}. Must run in the SAME transaction as the ledger
     * mutation it gates - never split across two transactions, and never called after the
     * ledger mutation (the claim must happen first so a losing caller never touches the
     * ledger at all).
     */
    boolean finalize(Long diagnosticRequestId, FinalizationOutcome outcome,
                      BigDecimal penaltyAmount, BigDecimal refundAmount, OffsetDateTime finalizedAt);
}
