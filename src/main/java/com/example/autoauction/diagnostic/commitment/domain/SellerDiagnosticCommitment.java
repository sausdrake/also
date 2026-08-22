package com.example.autoauction.diagnostic.commitment.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The seller's frozen commitment/guarantee tied to one diagnostic request - NOT the price
 * of diagnostics, a configurable guarantee amount that is held through the diagnostic/
 * auction lifecycle. Replaces the previous FrozenDeposit-based storage, which incorrectly
 * stored a diagnostic-request id in a column FK'd to auctions(id).
 *
 * PHASE 2: adds diagnosticFeeAmount (a second snapshot, alongside committedAmount) and
 * status (the commitment's own lifecycle state - see CommitmentStatus). Both
 * committedAmount and diagnosticFeeAmount are assigned exactly once, at creation, and are
 * never re-read from configuration afterward. penaltyAmount/refundAmount/
 * penaltyPolicyRef/finalizedAt and any commission/payment/settlement field remain
 * Phase 3+/out-of-aggregate scope, deliberately not introduced here.
 */
@Data
public class SellerDiagnosticCommitment {
    private Long id;
    private Long diagnosticRequestId;
    private Long depositId;
    private BigDecimal committedAmount;
    private BigDecimal diagnosticFeeAmount;
    private CommitmentStatus status;
    private OffsetDateTime frozenAt;
}
