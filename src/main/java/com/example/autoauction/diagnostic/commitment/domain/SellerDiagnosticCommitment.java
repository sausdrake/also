package com.example.autoauction.diagnostic.commitment.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * PHASE 1 (seller commitment redesign): the seller's frozen commitment/guarantee tied to
 * one diagnostic request - NOT the price of diagnostics, a configurable guarantee amount
 * that is held through the diagnostic/auction lifecycle. Replaces the previous
 * FrozenDeposit-based storage, which incorrectly stored a diagnostic-request id in a
 * column FK'd to auctions(id).
 *
 * Phase 1 scope only: a row's mere existence means "frozen"; there is no status/outcome
 * field here yet. Fee/penalty/refund/finalization-outcome fields are Phase 2's scope, not
 * introduced here.
 */
@Data
public class SellerDiagnosticCommitment {
    private Long id;
    private Long diagnosticRequestId;
    private Long depositId;
    private BigDecimal committedAmount;
    private OffsetDateTime frozenAt;
}
