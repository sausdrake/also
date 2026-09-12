-- Phase 3/3.1 (Identity & Verification MVP): current-state aggregate, append-only history, and
-- document ownership/metadata. UNVERIFIED is never a stored status - absence of a row for a
-- user IS "unverified" (UNIQUE(user_id) below), so this migration requires zero backfill/data
-- migration for existing users (Phase 3 §20).
--
-- The partial unique index below is the SOLE authoritative enforcement of the cross-account
-- uniqueness invariant (Phase 3 ADR-06) - "at most one VERIFIED account per
-- (canonical_document_number, issuing_country, document_type)". Application code never
-- substitutes for this constraint; it only reacts to it (Phase 3.1 §1).
--
-- No raw_document_number column exists anywhere (Phase 3.1 §5) - only the canonicalized value is
-- ever persisted, in both the current-state table and every history row.

CREATE TABLE identity_document (
    id                BIGSERIAL PRIMARY KEY,
    storage_key       VARCHAR(500) NOT NULL,
    uploader_user_id  BIGINT NOT NULL REFERENCES users(id),
    mime_type         VARCHAR(100),
    size_bytes        BIGINT,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_identity_document_storage_key UNIQUE (storage_key)
);

CREATE INDEX idx_identity_document_uploader_user_id ON identity_document(uploader_user_id);

CREATE TABLE identity_verification (
    id                          BIGSERIAL PRIMARY KEY,
    user_id                     BIGINT NOT NULL REFERENCES users(id),
    status                      VARCHAR(24) NOT NULL,
    issuing_country             VARCHAR(8) NOT NULL,
    document_type               VARCHAR(24) NOT NULL,
    canonical_document_number   VARCHAR(64) NOT NULL,
    current_document_id         BIGINT REFERENCES identity_document(id),
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    version                     BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_identity_verification_user_id UNIQUE (user_id),
    CONSTRAINT identity_verification_status_check
        CHECK (status IN ('PENDING_REVIEW', 'VERIFIED', 'REJECTED', 'REVOKED'))
);

-- Phase 3 ADR-06: enforced only while VERIFIED - a REJECTED/REVOKED row's identity is free to be
-- re-claimed by a different account, and PENDING_REVIEW is not yet a confirmed claim.
CREATE UNIQUE INDEX uk_identity_verification_verified_identity
    ON identity_verification(canonical_document_number, issuing_country, document_type)
    WHERE status = 'VERIFIED';

-- Admin pending queue (AdminIdentityVerificationService.listPending, ordered by created_at).
CREATE INDEX idx_identity_verification_status ON identity_verification(status);

CREATE TABLE identity_verification_history (
    id                          BIGSERIAL PRIMARY KEY,
    identity_verification_id    BIGINT NOT NULL REFERENCES identity_verification(id),
    event_type                  VARCHAR(24) NOT NULL,
    canonical_document_number   VARCHAR(64) NOT NULL,
    document_reference_id       BIGINT REFERENCES identity_document(id),
    -- No FK to users(id): mirrors the established convention for audit actor columns elsewhere
    -- in this schema (e.g. settlement_correction.corrected_by_admin_id, V44) - the actor's
    -- identity is recorded for audit purposes only, not enforced as a referential relationship.
    actor_admin_id               BIGINT,
    revocation_reason_code      VARCHAR(24),
    reason_text                 VARCHAR(1000),
    occurred_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT identity_verification_history_event_type_check
        CHECK (event_type IN ('SUBMISSION', 'APPROVAL', 'REJECTION', 'REVOCATION', 'FAILED_APPROVAL_ATTEMPT')),
    CONSTRAINT identity_verification_history_revocation_reason_check
        CHECK (revocation_reason_code IS NULL
            OR revocation_reason_code IN ('ADMIN_ERROR', 'FRAUD_DISCOVERED', 'DOCUMENT_INVALID', 'IDENTITY_MISMATCH', 'OTHER')),
    -- Mirrors IdentityVerificationHistory.validate()'s exact per-event-type shape rules.
    CONSTRAINT identity_verification_history_shape_check
        CHECK (
            (event_type = 'SUBMISSION' AND document_reference_id IS NOT NULL AND actor_admin_id IS NULL
                AND revocation_reason_code IS NULL AND reason_text IS NULL)
            OR (event_type = 'APPROVAL' AND document_reference_id IS NULL AND actor_admin_id IS NOT NULL
                AND revocation_reason_code IS NULL AND reason_text IS NULL)
            OR (event_type = 'REJECTION' AND document_reference_id IS NULL AND actor_admin_id IS NOT NULL
                AND revocation_reason_code IS NULL AND reason_text IS NOT NULL)
            OR (event_type = 'REVOCATION' AND document_reference_id IS NULL AND actor_admin_id IS NOT NULL
                AND revocation_reason_code IS NOT NULL
                AND (revocation_reason_code != 'OTHER' OR reason_text IS NOT NULL))
            OR (event_type = 'FAILED_APPROVAL_ATTEMPT' AND document_reference_id IS NULL AND actor_admin_id IS NOT NULL
                AND revocation_reason_code IS NULL)
        )
);

CREATE INDEX idx_identity_verification_history_identity_verification_id
    ON identity_verification_history(identity_verification_id);
