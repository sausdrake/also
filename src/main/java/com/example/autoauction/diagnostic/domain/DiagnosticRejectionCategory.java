package com.example.autoauction.diagnostic.domain;

/**
 * PHASE 2: structured, machine-readable classification of why a diagnostic report was
 * rejected - replaces free-text-only rejectedReason as the basis for any future financial
 * decision. Deliberately outcome-neutral in Phase 2: recording a category here has no
 * financial effect of its own; a later finalization phase reads it to decide between
 * Outcome B (VEHICLE_CONDITION) and Outcome C (SELLER_VIOLATION).
 *
 * VEHICLE_CONDITION: the vehicle itself did not meet the platform's diagnostic
 * requirements - NOT a seller violation.
 * SELLER_VIOLATION: the seller violated an applicable platform rule.
 *
 * No other category exists. Do not infer one from rejectedReason free text.
 */
public enum DiagnosticRejectionCategory {
    VEHICLE_CONDITION,
    SELLER_VIOLATION
}
