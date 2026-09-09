package com.example.autoauction.diagnostic.commitment.domain;

/**
 * Lifecycle status of a SellerDiagnosticCommitment.
 *
 * FROZEN: the commitment is active; the diagnostic lifecycle has not yet concluded.
 * AWAITING_FINALIZATION: the diagnostic lifecycle has concluded (approved, or rejected
 * with a recorded DiagnosticRejectionCategory) but the financial outcome (fee/penalty/
 * refund split) has NOT yet been computed.
 * FINALIZED (PHASE 3B): the financial outcome has been computed and the ledger mutated -
 * which outcome (A/B/C/future) is recorded separately on {@link FinalizationOutcome}, kept
 * off this enum deliberately so the lifecycle machine stays stable as outcomes are added.
 * Terminal - no further transition exists for a FINALIZED commitment in this phase.
 *
 * No status should be inferred for unresolved future scenarios (seller no-show,
 * post-approval withdrawal, refusal after sale, stacked violations) - those remain
 * unrepresented until their own business rules are authorized.
 */
public enum CommitmentStatus {
    FROZEN,
    AWAITING_FINALIZATION,
    FINALIZED
}
