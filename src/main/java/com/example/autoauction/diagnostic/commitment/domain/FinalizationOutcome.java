package com.example.autoauction.diagnostic.commitment.domain;

/**
 * PHASE 3B: which financial outcome a FINALIZED SellerDiagnosticCommitment resolved to.
 * Deliberately a separate enum from {@link CommitmentStatus} (lifecycle progress) rather
 * than folded into it as FINALIZED_A/FINALIZED_B/FINALIZED_C - the business-rule freeze
 * requires future outcomes (and the currently-unresolved no-show/withdrawal/refusal/
 * stacked-violation scenarios) to remain representable and extensible without a closed
 * switch; a status/outcome split keeps the lifecycle state machine stable while this enum
 * grows independently.
 *
 * B: auction reached a legitimate no-sale outcome with no seller violation, OR the
 * diagnostic report was rejected for VEHICLE_CONDITION - fee charged, zero penalty,
 * remainder refunded. The only outcome implemented in Phase 3B.
 *
 * A and C are explicitly NOT added here yet - they require dependencies (an auction/
 * buyer-payment/commission signal for A; an authorized ViolationPolicy for C) that do not
 * exist yet. Adding them prematurely as unused values would misrepresent them as available.
 */
public enum FinalizationOutcome {
    B
}
