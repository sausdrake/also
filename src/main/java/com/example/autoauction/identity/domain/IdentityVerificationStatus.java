package com.example.autoauction.identity.domain;

/**
 * Phase 3/3.1: current-state lifecycle for {@link IdentityVerification}. {@code UNVERIFIED} is
 * deliberately NOT a value here - it is represented by the absence of a row for a given user,
 * per the locked architecture (zero data migration, zero fabricated rows for users who never
 * submit anything).
 */
public enum IdentityVerificationStatus {
    PENDING_REVIEW,
    VERIFIED,
    REJECTED,
    REVOKED
}
