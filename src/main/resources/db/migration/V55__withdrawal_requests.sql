-- F6: manual payout requests. Until a real bank is connected, a withdrawal is a request that an
-- admin pays out by hand: PENDING -> PROCESSING -> PAID, or PENDING -> REJECTED.
--
-- This table holds only the request lifecycle - never money. Every money movement stays in
-- deposit_transactions: a WITHDRAWAL row debits the balance when the request is created (so the
-- amount can no longer be used by anything else), and a WITHDRAWAL_RETURN row with idempotency
-- key 'WITHDRAWAL_RETURN:<id>' credits it back on rejection - the existing unique index on
-- deposit_transactions.idempotency_key (V21) allows at most one return per request. PAID moves no
-- money: it only records that AlSo paid the user by hand.
CREATE TABLE withdrawal_requests (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT NOT NULL REFERENCES users(id),
    amount                  NUMERIC(19,2) NOT NULL,
    status                  VARCHAR(16) NOT NULL,
    idempotency_key         VARCHAR(100) NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    processing_started_at   TIMESTAMP WITH TIME ZONE,
    processing_started_by   BIGINT,
    processed_at            TIMESTAMP WITH TIME ZONE,
    processed_by            BIGINT,
    rejection_reason        VARCHAR(1000),
    version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_withdrawal_requests_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT withdrawal_requests_amount_pos_check CHECK (amount > 0),
    CONSTRAINT withdrawal_requests_status_check
        CHECK (status IN ('PENDING', 'PROCESSING', 'PAID', 'REJECTED')),
    -- the audit columns of each state are present exactly when that state requires them
    CONSTRAINT withdrawal_requests_state_audit_check CHECK (
        (status = 'PENDING'
            AND processing_started_at IS NULL AND processing_started_by IS NULL
            AND processed_at IS NULL AND processed_by IS NULL AND rejection_reason IS NULL)
        OR (status = 'PROCESSING'
            AND processing_started_at IS NOT NULL AND processing_started_by IS NOT NULL
            AND processed_at IS NULL AND processed_by IS NULL AND rejection_reason IS NULL)
        OR (status = 'PAID'
            AND processing_started_at IS NOT NULL AND processing_started_by IS NOT NULL
            AND processed_at IS NOT NULL AND processed_by IS NOT NULL AND rejection_reason IS NULL)
        OR (status = 'REJECTED'
            AND processing_started_at IS NULL AND processing_started_by IS NULL
            AND processed_at IS NOT NULL AND processed_by IS NOT NULL AND rejection_reason IS NOT NULL)
    )
);

CREATE INDEX idx_withdrawal_requests_user_created ON withdrawal_requests(user_id, created_at);
CREATE INDEX idx_withdrawal_requests_status_created ON withdrawal_requests(status, created_at);
