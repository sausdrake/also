package com.example.autoauction.diagnostic.commitment.domain.port;

import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;

import java.util.Optional;

public interface SellerDiagnosticCommitmentRepository {

    SellerDiagnosticCommitment save(SellerDiagnosticCommitment commitment);

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
}
