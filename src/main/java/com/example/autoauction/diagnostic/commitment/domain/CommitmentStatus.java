package com.example.autoauction.diagnostic.commitment.domain;

/**
 * PHASE 2: lifecycle status of a SellerDiagnosticCommitment.
 *
 * FROZEN: the commitment is active; the diagnostic lifecycle has not yet concluded.
 * AWAITING_FINALIZATION: the diagnostic lifecycle has concluded (approved, or rejected
 * with a recorded DiagnosticRejectionCategory) but the financial outcome (fee/penalty/
 * refund split) has NOT yet been computed - that is Phase 3's responsibility, not
 * represented here. No other status exists in Phase 2, and none should be inferred for
 * unresolved future scenarios (seller no-show, post-approval withdrawal, refusal after
 * sale, stacked violations) - those remain unrepresented until their own business rules
 * are authorized.
 */
public enum CommitmentStatus {
    FROZEN,
    AWAITING_FINALIZATION
}
