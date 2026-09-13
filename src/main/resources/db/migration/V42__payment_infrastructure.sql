-- SLICE 2 (Bank / Payment Infrastructure) - Phase 3A provider-agnostic foundation.
--
-- Purely additive: no existing table, column, row, constraint, index or trigger from V1-V41 is
-- touched. Introduces the deposit-top-up payment aggregate and its audit/reconciliation tables,
-- plus one new nullable audit column on deposit_transactions.
--
-- Scope discipline: this migration does NOT touch the Slice 1 winning-price payment_evidence /
-- payment_refund_evidence tables (a different, closed money circuit), does NOT add a deposit
-- refund aggregate (deposit-refund automation is out of MVP), and does NOT alter deposit /
-- deposit_transactions balance semantics or the V21 idempotency behaviour. The
-- performed_by_admin_id column added here is only populated by Step 2's controlled adminAdjust
-- hardening; existing rows and all non-ADMIN_ADJUSTMENT transaction types remain NULL.
--
-- Currency: KGS only (this codebase has no Currency abstraction, by explicit design). The
-- payment.currency column exists solely to make that assumption explicit and DB-enforced at the
-- payment boundary (CHECK currency = 'KGS') - it is not a step toward multi-currency.
--
-- Money precision: NUMERIC(19,2), matching this project's ledger/transaction-amount convention
-- (deposits.balance, deposit_transactions.amount, payment_evidence.amount). A superset of the
-- NUMERIC(10,2) auction-pricing columns; never truncating.
--
-- FK convention (V28/V34 corrected era): a reference whose row is meaningless without its parent
-- gets a real FK with NO ON DELETE clause (restrictive NO ACTION) - these are durable financial /
-- audit facts, never cascaded away. Actor/audit ids (resolved_by_admin_id, performed_by_admin_id)
-- stay plain BIGINT with no FK, exactly like auctions.created_by / winner_id and
-- payment_evidence.received_by_admin_id.
--
-- Immutability: enforced primarily by immutable domain design (no public setters on Payment
-- write-once fields, append-only PaymentEvent, guarded ReconciliationCase.resolve) plus the
-- NOT NULL / CHECK / UNIQUE constraints below - the same mechanism as payment_evidence /
-- handover_evidence, no trigger.
--
-- Uniqueness rationale:
--   payment.merchant_reference UNIQUE          - a provider callback resolves exactly one payment;
--                                               the forged-reference guard.
--   payment.provider_transaction_id UNIQUE     - the primary double-credit / duplicate-webhook
--     (partial, WHERE NOT NULL)                  guard; globally unique once known.
--   payment.creation_idempotency_key UNIQUE    - client create-retry dedup; partial, only where a
--     (partial, WHERE NOT NULL)                  key was actually supplied (NULLs never collide).
--   reconciliation_case (payment_id, type) UNIQUE - at most one open case of each type per payment.

CREATE TABLE payment (
    id                        BIGSERIAL PRIMARY KEY,
    user_id                   BIGINT NOT NULL REFERENCES users(id),
    merchant_reference        VARCHAR(64)  NOT NULL UNIQUE,
    amount_requested          NUMERIC(19,2) NOT NULL,
    amount_confirmed          NUMERIC(19,2),
    currency                  VARCHAR(3)   NOT NULL DEFAULT 'KGS',
    status                    VARCHAR(16)  NOT NULL,
    provider_transaction_id   VARCHAR(128),
    creation_idempotency_key  VARCHAR(100),
    late_success_after_expiry BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT payment_currency_kgs_check
        CHECK (currency = 'KGS'),
    CONSTRAINT payment_amount_requested_pos_check
        CHECK (amount_requested > 0),
    CONSTRAINT payment_amount_confirmed_pos_check
        CHECK (amount_confirmed IS NULL OR amount_confirmed > 0),
    CONSTRAINT payment_status_check
        CHECK (status IN ('CREATED', 'PENDING', 'SUCCEEDED', 'FAILED', 'EXPIRED', 'CANCELLED'))
);

CREATE UNIQUE INDEX uk_payment_provider_transaction_id
    ON payment(provider_transaction_id)
    WHERE provider_transaction_id IS NOT NULL;

CREATE UNIQUE INDEX uk_payment_creation_idempotency_key
    ON payment(creation_idempotency_key)
    WHERE creation_idempotency_key IS NOT NULL;

CREATE INDEX idx_payment_status         ON payment(status);
CREATE INDEX idx_payment_user_created   ON payment(user_id, created_at);

CREATE TABLE payment_event (
    id          BIGSERIAL PRIMARY KEY,
    payment_id  BIGINT NOT NULL REFERENCES payment(id),
    type        VARCHAR(32) NOT NULL,
    source      VARCHAR(24) NOT NULL,
    raw_payload TEXT,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT payment_event_type_check
        CHECK (type IN ('CREATED', 'PENDING', 'SUCCEEDED', 'FAILED', 'EXPIRED', 'CANCELLED',
                        'CALLBACK_RECEIVED', 'POLL_RESULT', 'RECONCILIATION')),
    CONSTRAINT payment_event_source_check
        CHECK (source IN ('CLIENT', 'PROVIDER_WEBHOOK', 'PROVIDER_POLL', 'ADMIN', 'SCHEDULER'))
);

CREATE INDEX idx_payment_event_payment_id ON payment_event(payment_id);

CREATE TABLE reconciliation_case (
    id                   BIGSERIAL PRIMARY KEY,
    payment_id           BIGINT NOT NULL REFERENCES payment(id),
    type                 VARCHAR(24) NOT NULL,
    status               VARCHAR(12) NOT NULL DEFAULT 'OPEN',
    opened_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_by_admin_id BIGINT,
    resolution_note      TEXT,
    resolved_at          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT reconciliation_case_type_check
        CHECK (type IN ('MISMATCH', 'DUPLICATE', 'STUCK', 'LATE_AFTER_EXPIRY')),
    CONSTRAINT reconciliation_case_status_check
        CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT uk_reconciliation_case_payment_type
        UNIQUE (payment_id, type)
);

CREATE INDEX idx_reconciliation_case_status ON reconciliation_case(status);

-- Structured admin-actor attribution for manual deposit adjustments. Nullable: existing rows and
-- every non-ADMIN_ADJUSTMENT transaction type are unaffected and remain NULL. Step 2 enforces
-- NOT NULL at the application layer for new ADMIN_ADJUSTMENT rows. Plain BIGINT, no FK - matches
-- this project's actor/audit-id convention (deposit_transactions.user_id, auctions.created_by).
ALTER TABLE deposit_transactions ADD COLUMN performed_by_admin_id BIGINT;
