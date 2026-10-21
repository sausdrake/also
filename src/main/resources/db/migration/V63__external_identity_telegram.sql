-- AUTH-TELEGRAM-01: sign-in with an external identity provider (Telegram, OIDC Authorization Code + PKCE).
-- Additive and backward compatible: no existing row changes, no column is dropped, password login is untouched.

-- A Telegram-created account has no email (email is optional contact data, not a login factor). UNIQUE is
-- kept - PostgreSQL allows any number of NULLs under a UNIQUE constraint. Existing rows keep their email.
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;

-- The external identity is NOT the account: provider + stable subject (OIDC `sub`), attached to exactly one user.
-- UNIQUE(provider, provider_subject) is the authoritative guarantee against two accounts for one identity (also
-- under concurrent first logins); UNIQUE(user_id, provider) - one Telegram identity per account.
-- Display data (name, @username) is metadata refreshed on every login, never an identifier. A phone is stored only
-- when the provider marked it verified (optional `phone` scope). ON DELETE CASCADE: the identity never blocks the
-- account's own lifecycle.
CREATE TABLE external_identity (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider            VARCHAR(16)  NOT NULL,
    provider_subject    VARCHAR(128) NOT NULL,
    display_name        VARCHAR(255),
    provider_username   VARCHAR(64),
    phone_number        VARCHAR(32),
    phone_verified_at   TIMESTAMP WITH TIME ZONE,
    bot_access          VARCHAR(16)  NOT NULL DEFAULT 'NOT_REQUESTED',
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    last_login_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_external_identity_provider_subject UNIQUE (provider, provider_subject),
    CONSTRAINT uk_external_identity_user_provider UNIQUE (user_id, provider),
    CONSTRAINT external_identity_provider_check CHECK (provider IN ('TELEGRAM')),
    CONSTRAINT external_identity_bot_access_check CHECK (bot_access IN ('NOT_REQUESTED', 'GRANTED')),
    CONSTRAINT external_identity_phone_check CHECK ((phone_number IS NULL) = (phone_verified_at IS NULL))
);

-- One OIDC round trip, server-side: hashed state (CSRF), hashed browser-binding secret (login CSRF), PKCE verifier
-- and nonce that never leave the server, purpose, and for a link the authenticated user that started it.
-- Single use (consumed_at) and short-lived (expires_at).
CREATE TABLE oidc_authorization_request (
    id              BIGSERIAL PRIMARY KEY,
    state_hash      VARCHAR(64)  NOT NULL,
    binding_hash    VARCHAR(64)  NOT NULL,
    code_verifier   VARCHAR(128) NOT NULL,
    nonce           VARCHAR(64)  NOT NULL,
    purpose         VARCHAR(8)   NOT NULL,
    link_user_id    BIGINT REFERENCES users (id) ON DELETE CASCADE,
    scope           VARCHAR(128) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_oidc_authorization_request_state UNIQUE (state_hash),
    CONSTRAINT oidc_authorization_request_purpose_check CHECK (purpose IN ('LOGIN', 'LINK')),
    CONSTRAINT oidc_authorization_request_link_check CHECK ((purpose = 'LINK') = (link_user_id IS NOT NULL))
);

CREATE INDEX idx_oidc_authorization_request_expires_at ON oidc_authorization_request (expires_at);
