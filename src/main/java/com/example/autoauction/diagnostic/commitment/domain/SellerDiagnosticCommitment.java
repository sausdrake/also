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
 * never re-read from configuration afterward.
 *
 * PHASE 3B: adds finalizationOutcome/finalizedAt/penaltyAmount/refundAmount, populated
 * exactly once when the commitment transitions AWAITING_FINALIZATION -> FINALIZED. Only
 * Outcome B is computed in this phase. penaltyPolicyRef and any commission/payment/
 * settlement field remain out-of-aggregate scope, deliberately not introduced here - see
 * the Phase 3 business-rule freeze for why commission/payment belong to a separate
 * Settlement concern, never to this entity.
 *
 * X3-E: adds policyVersionId - the PolicyVersion published at the moment
 * freezeCommitment() ran, snapshotted once and never re-read or reassigned afterward. This
 * is the diagnostic/commitment-stage policy reference, deliberately independent from
 * Auction.policyVersionId (its own, separately-snapshotted-at-auction-creation-time
 * reference) - the two are allowed to diverge (locked X3-E business decision) and are never
 * synchronized. Nullable at the column level only because commitments frozen before X3-E
 * predate this field and are never backfilled - the diagnostic-stage seller-violation
 * trigger fails closed for those rows.
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
    private FinalizationOutcome finalizationOutcome;
    private OffsetDateTime finalizedAt;
    private BigDecimal penaltyAmount;
    private BigDecimal refundAmount;
    private Long policyVersionId;
}
