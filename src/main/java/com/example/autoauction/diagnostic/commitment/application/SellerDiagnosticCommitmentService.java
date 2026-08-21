// diagnostic/commitment/application/SellerDiagnosticCommitmentService.java
package com.example.autoauction.diagnostic.commitment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
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

    public SellerDiagnosticCommitmentService(SellerDiagnosticCommitmentRepository repository,
                                             DepositService depositService) {
        this.repository = repository;
        this.depositService = depositService;
    }

    /**
     * Freezes the configured commitment amount for a newly-created diagnostic request and
     * records the correctly-typed commitment. Shares the caller's transaction (no
     * REQUIRES_NEW) - a freeze failure must abort the whole diagnostic-request creation,
     * matching current behavior exactly (DiagnosticRequestService.createRequest has no
     * try/catch around this call).
     */
    @Transactional
    public void freezeCommitment(Long sellerId, Long diagnosticRequestId, BigDecimal amount) {
        if (repository.existsByDiagnosticRequestId(diagnosticRequestId)) {
            throw new IllegalStateException(
                    "Seller diagnostic commitment already exists for request " + diagnosticRequestId);
        }

        Long depositId = depositService.getDepositByUserId(sellerId).id();

        // Mutates the Deposit ledger (balance/blockedAmount) and records the
        // DepositTransaction - DepositService remains solely responsible for that; this
        // service never touches Deposit internals directly.
        depositService.freezeForDiagnosticRequest(sellerId, diagnosticRequestId, amount);

        SellerDiagnosticCommitment commitment = new SellerDiagnosticCommitment();
        commitment.setDiagnosticRequestId(diagnosticRequestId);
        commitment.setDepositId(depositId);
        commitment.setCommittedAmount(amount);
        commitment.setFrozenAt(OffsetDateTime.now());
        repository.save(commitment);

        log.info("Seller diagnostic commitment frozen: request={}, seller={}, amount={}",
                diagnosticRequestId, sellerId, amount);
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
