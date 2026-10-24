-- LEGAL-DATA-01: versioned legal documents and the record of who accepted which exact version.
-- PRIVACY-REQ-01: users' requests about their personal data and the administrators' answers.
-- New tables only; nothing existing changes.

CREATE TABLE legal_document_version (
    id              BIGSERIAL PRIMARY KEY,
    document_type   VARCHAR(40)  NOT NULL,
    version         VARCHAR(40)  NOT NULL,
    title           VARCHAR(200) NOT NULL,
    content         TEXT         NOT NULL,
    content_sha256  VARCHAR(64)  NOT NULL,
    acceptance_kind VARCHAR(20)  NOT NULL,
    status          VARCHAR(30)  NOT NULL,
    created_by      BIGINT       NOT NULL REFERENCES users(id),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    submitted_at    TIMESTAMP WITH TIME ZONE,
    approved_by     BIGINT REFERENCES users(id),
    approved_at     TIMESTAMP WITH TIME ZONE,
    approval_note   VARCHAR(1000),
    activated_by    BIGINT REFERENCES users(id),
    effective_at    TIMESTAMP WITH TIME ZONE,
    retired_at      TIMESTAMP WITH TIME ZONE,
    version_lock    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_legal_document_type_version UNIQUE (document_type, version),
    CONSTRAINT legal_document_type_check CHECK (document_type IN ('TERMS_OF_USE', 'PRIVACY_POLICY',
        'PERSONAL_DATA_PROCESSING_POLICY', 'AUCTION_RULES', 'KYC_POLICY', 'DIAGNOSTIC_RULES', 'FINANCIAL_RULES')),
    CONSTRAINT legal_document_status_check CHECK (status IN ('DRAFT', 'PENDING_LEGAL_REVIEW', 'APPROVED', 'ACTIVE', 'RETIRED')),
    CONSTRAINT legal_document_kind_check CHECK (acceptance_kind IN ('ACCEPTANCE', 'ACKNOWLEDGEMENT', 'CONSENT', 'INFORMATIONAL')),
    -- maker-checker: the author never approves their own text
    CONSTRAINT legal_document_maker_checker CHECK (approved_by IS NULL OR approved_by <> created_by),
    CONSTRAINT legal_document_approval_consistency CHECK (
        status IN ('DRAFT', 'PENDING_LEGAL_REVIEW')
        OR (approved_by IS NOT NULL AND approved_at IS NOT NULL AND approval_note IS NOT NULL)),
    CONSTRAINT legal_document_active_consistency CHECK (
        status <> 'ACTIVE' OR (effective_at IS NOT NULL AND activated_by IS NOT NULL))
);

-- at most one ACTIVE version per document type
CREATE UNIQUE INDEX uk_legal_document_one_active ON legal_document_version (document_type) WHERE status = 'ACTIVE';

CREATE TABLE legal_acceptance (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT      NOT NULL REFERENCES users(id),
    document_version_id BIGINT      NOT NULL REFERENCES legal_document_version(id),
    document_type       VARCHAR(40) NOT NULL,
    document_version    VARCHAR(40) NOT NULL,
    content_sha256      VARCHAR(64) NOT NULL,
    kind                VARCHAR(20) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    source              VARCHAR(30) NOT NULL,
    accepted_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    ip_address          VARCHAR(64),
    user_agent          VARCHAR(512),
    withdrawn_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_legal_acceptance_user_version UNIQUE (user_id, document_version_id),
    CONSTRAINT legal_acceptance_status_check CHECK (status IN ('ACCEPTED', 'WITHDRAWN')),
    CONSTRAINT legal_acceptance_kind_check CHECK (kind IN ('ACCEPTANCE', 'ACKNOWLEDGEMENT', 'CONSENT')),
    CONSTRAINT legal_acceptance_source_check CHECK (source IN ('REGISTRATION', 'RE_ACCEPTANCE', 'PROFILE')),
    -- only a consent is ever withdrawn
    CONSTRAINT legal_acceptance_withdrawal_check CHECK (
        (status = 'ACCEPTED' AND withdrawn_at IS NULL)
        OR (status = 'WITHDRAWN' AND kind = 'CONSENT' AND withdrawn_at IS NOT NULL))
);

CREATE INDEX idx_legal_acceptance_user ON legal_acceptance (user_id);

CREATE TABLE privacy_request (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT        NOT NULL REFERENCES users(id),
    type        VARCHAR(30)   NOT NULL,
    message     VARCHAR(4000) NOT NULL,
    status      VARCHAR(20)   NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    taken_by    BIGINT REFERENCES users(id),
    taken_at    TIMESTAMP WITH TIME ZONE,
    answered_by BIGINT REFERENCES users(id),
    answer      VARCHAR(4000),
    answered_at TIMESTAMP WITH TIME ZONE,
    version     BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT privacy_request_type_check CHECK (type IN ('ACCESS', 'CORRECTION', 'DELETION', 'OBJECTION',
        'CONSENT_WITHDRAWAL', 'OTHER')),
    CONSTRAINT privacy_request_status_check CHECK (status IN ('OPEN', 'IN_PROGRESS', 'ANSWERED', 'REJECTED')),
    CONSTRAINT privacy_request_answer_check CHECK (
        status IN ('OPEN', 'IN_PROGRESS')
        OR (answer IS NOT NULL AND answered_by IS NOT NULL AND answered_at IS NOT NULL))
);

CREATE INDEX idx_privacy_request_user ON privacy_request (user_id);
CREATE INDEX idx_privacy_request_status ON privacy_request (status);
