package com.example.autoauction.identity.domain;

/**
 * Phase 3 §6/§11: discriminates one {@link IdentityVerificationHistory} row. Every value except
 * {@code SUBMISSION} carries a mandatory admin actor - every identity decision is inherently a
 * human admin action, there is no system-generated case here (unlike e.g. a payment deadline).
 */
public enum IdentityEventType {
    /** User-submitted, actor is the subject themselves (captured on the parent row, not here). */
    SUBMISSION,
    APPROVAL,
    REJECTION,
    REVOCATION,
    /** Phase 3.1 §1: the losing side of a concurrent-approval race - fraud-relevant, always recorded. */
    FAILED_APPROVAL_ATTEMPT
}
