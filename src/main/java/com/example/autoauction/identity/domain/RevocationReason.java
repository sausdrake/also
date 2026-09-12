package com.example.autoauction.identity.domain;

/**
 * Phase 2.5: the minimum required revocation-reason catalogue - deliberately small (5 values),
 * not a generalized taxonomy. Free-text elaboration is mandatory only for {@link #OTHER}.
 */
public enum RevocationReason {
    ADMIN_ERROR,
    FRAUD_DISCOVERED,
    DOCUMENT_INVALID,
    IDENTITY_MISMATCH,
    OTHER
}
