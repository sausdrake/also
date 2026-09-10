-- Slice 1 (Sale Completion & Failure API): the immutable, admin-witnessed fact that the full
-- winning price physically received under a payment_evidence row was returned in full to the
-- buyer, because the sale was finalized as SALE_FAILED after payment had already occurred.
--
-- Locked business rule: on EVERY SALE_FAILED outcome (B, BUYER_FAULT, PLATFORM_FAULT, C), if a
-- payment_evidence row exists for the auction, the entire winning price is returned to the
-- buyer. There is no partial retention, no winning-price penalty, no wallet, no settlement
-- subsystem. This table records only that the return happened; the amount is always exactly
-- the referenced payment_evidence.amount (enforced in the application layer -
-- PaymentRefundService derives it from the loaded payment_evidence and never accepts it as
-- input; a cross-table equality cannot be a Postgres CHECK).
--
-- Purely additive: no existing table, column, row, or constraint is touched.
--
-- FK convention: follows V34+'s corrected convention (no ON DELETE clause -> RESTRICT/NO
-- ACTION). These rows are durable, once-true-forever business facts, not disposable child
-- records; deleting a payment_evidence or auctions row that a refund fact still references is
-- blocked, not silently cascaded away. No code path in this codebase deletes an Auction,
-- payment_evidence, or payment_refund_evidence row.
--
-- Immutability: enforced through immutable domain design (no setters on PaymentRefundEvidence,
-- no update/delete methods on its repository port) plus the NOT NULL / UNIQUE / CHECK
-- constraints below - same mechanism as payment_evidence / handover_evidence, no trigger.
--
-- payment_evidence_id UNIQUE: one refund fact per payment fact, ever - the primary
-- duplicate-refund guard. auction_id UNIQUE: a second, independent "one refund per auction"
-- guarantee (transitively implied by payment_evidence.auction_id's own UNIQUE, kept directly
-- for auditability/queryability, matching sale_resolution's own redundant-but-direct
-- auction_id column).
--
-- Amount precision: NUMERIC(19,2), matching payment_evidence.amount and this project's
-- ledger-amount convention.
--
-- No index beyond what UNIQUE/FK already provide - no query in this slice needs one (same
-- discipline as V33/V34).

CREATE TABLE payment_refund_evidence (
    id                     BIGSERIAL PRIMARY KEY,
    payment_evidence_id    BIGINT NOT NULL UNIQUE REFERENCES payment_evidence(id),
    auction_id             BIGINT NOT NULL UNIQUE REFERENCES auctions(id),
    buyer_id               BIGINT NOT NULL,
    refunded_amount        NUMERIC(19,2) NOT NULL,
    refunded_by_admin_id   BIGINT NOT NULL,
    refunded_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT payment_refund_evidence_amount_positive_check CHECK (refunded_amount > 0)
);
