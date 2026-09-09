package com.example.autoauction.diagnostic.commitment.domain;

/**
 * PHASE 3B / X3-D: which financial outcome a FINALIZED SellerDiagnosticCommitment resolved
 * to. Deliberately a separate enum from {@link CommitmentStatus} (lifecycle progress) rather
 * than folded into it as FINALIZED_A/FINALIZED_B/FINALIZED_C - the business-rule freeze
 * requires future outcomes (and the currently-unresolved no-show/withdrawal/refusal/
 * stacked-violation scenarios) to remain representable and extensible without a closed
 * switch; a status/outcome split keeps the lifecycle state machine stable while this enum
 * grows independently.
 *
 * A (X3-D): the commitment's diagnostic cycle concluded with no fault attributable to the
 * seller and no diagnostic fee owed - zero penalty, zero fee, full refund. Recorded for
 * three distinct Auction-level triggers that share this exact financial shape: a successful
 * sale (SUCCESS), a buyer-at-fault failed sale (BUYER_FAULT), and a platform-at-fault failed
 * sale (PLATFORM_FAULT). The three triggers remain distinguishable via SaleResolution
 * (joined through Auction.sellerDiagnosticCommitmentId), never by this enum - A intentionally
 * does not gain BUYER_FAULT/PLATFORM_FAULT siblings, since their financial shape is identical
 * and this enum tracks financial shape, not the triggering event.
 *
 * B: auction reached a legitimate no-sale outcome with no seller violation, OR the
 * diagnostic report was rejected for VEHICLE_CONDITION - fee charged, zero penalty,
 * remainder refunded. Reachable via two independent triggers (see
 * SellerDiagnosticCommitmentFinalizationService.finalizeAsOutcomeB for the diagnostic-
 * rejection trigger, SaleCommitmentResolutionOrchestrator for the Auction-outcome trigger) -
 * both converge on this same value and the same underlying claim-first UPDATE.
 *
 * C is explicitly NOT added here yet - it requires an authorized ViolationPolicy that does
 * not exist yet. Adding it prematurely as an unused value would misrepresent it as available.
 */
public enum FinalizationOutcome {
    A,
    B
}
