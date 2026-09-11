-- Step 2C (Sale Financial Settlement): Settlement/SettlementCorrection foundation plus the
-- persisted Buy Now discriminator Settlement creation depends on.
--
-- Purely additive: no existing table, column, row, constraint, or index from V1-V43 is touched.
--
-- sale_finalization_method: nullable discriminator on `auctions`, distinguishing an ordinary
-- winning bid from a Buy Now sale - needed because the buyer-facing Buy Now amount cannot be
-- safely reverse-engineered as an ordinary auction bid (the forward commission-markup rounding
-- is not reliably invertible). NULL for every historical auction finalized before this column
-- existed - deliberately NOT backfilled; Settlement creation fails closed for a NULL value
-- rather than guessing. New finalization paths (AuctionFinalizer.finalizeAuction/
-- finalizeWithBuyNow) always populate it going forward.
--
-- settlement: the evidence-oriented record of a successful sale's seller-payout consequence.
-- There is no seller wallet and no platform wallet - this table records only the computed
-- economics of the sale (expected_*, fixed forever at creation) plus, later, an admin's
-- attestation that the payout was physically handed to the seller (attested_payout_amount/
-- recorded_by_admin_id/recorded_at). UNIQUE(auction_id) mirrors sale_resolution's own
-- one-resolution-per-auction precedent. Money precision NUMERIC(19,2), matching this project's
-- ledger/transaction-amount convention (deposits.balance, deposit_transactions.amount,
-- payment.amount_requested). The conservation CHECK mirrors the domain's own constructor
-- invariant as a second, independent guard (the same "defense in depth, both layers" convention
-- already used throughout this codebase, e.g. payment_amount_*_pos_check).
--
-- settlement_correction: immutable, append-only corrections layered on top of an existing
-- settlement row - the original settlement is never mutated. Zero or more per settlement (no
-- uniqueness on settlement_id). idempotency_key carries its own UNIQUE constraint, mirroring
-- deposit_transactions.idempotency_key (V21) - a correction attempt's own per-attempt retry key,
-- not a content fingerprint, since multiple legitimately-distinct corrections with identical
-- values are explicitly permitted. The CHECK constraint enforces the exact type<->value
-- combination the domain's own constructor already validates (AMOUNT_CORRECTION requires
-- corrected_amount and forbids corrected_seller_id; SELLER_CORRECTION the reverse;
-- PAYOUT_INVALIDATED forbids both).

ALTER TABLE auctions
    ADD COLUMN sale_finalization_method VARCHAR(16);

ALTER TABLE auctions
    ADD CONSTRAINT auctions_sale_finalization_method_check
        CHECK (sale_finalization_method IS NULL OR sale_finalization_method IN ('ORDINARY_BID', 'BUY_NOW'));

CREATE TABLE settlement (
    id                          BIGSERIAL PRIMARY KEY,
    auction_id                  BIGINT NOT NULL REFERENCES auctions(id),
    seller_id                   BIGINT NOT NULL,
    expected_buyer_paid_amount  NUMERIC(19,2) NOT NULL,
    expected_seller_amount      NUMERIC(19,2) NOT NULL,
    expected_commission         NUMERIC(19,2) NOT NULL,
    status                      VARCHAR(16) NOT NULL,
    attested_payout_amount      NUMERIC(19,2),
    recorded_by_admin_id        BIGINT,
    recorded_at                 TIMESTAMP WITH TIME ZONE,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT settlement_status_check
        CHECK (status IN ('PENDING', 'PAYOUT_RECORDED')),
    CONSTRAINT settlement_amounts_positive_check
        CHECK (expected_buyer_paid_amount > 0 AND expected_seller_amount > 0
               AND expected_commission >= 0
               AND (attested_payout_amount IS NULL OR attested_payout_amount > 0)),
    CONSTRAINT settlement_conservation_check
        CHECK (expected_seller_amount + expected_commission = expected_buyer_paid_amount)
);

CREATE UNIQUE INDEX uk_settlement_auction_id ON settlement(auction_id);

CREATE TABLE settlement_correction (
    id                     BIGSERIAL PRIMARY KEY,
    settlement_id          BIGINT NOT NULL REFERENCES settlement(id),
    correction_type        VARCHAR(24) NOT NULL,
    corrected_amount        NUMERIC(19,2),
    corrected_seller_id    BIGINT,
    reason                 TEXT NOT NULL,
    corrected_by_admin_id  BIGINT NOT NULL,
    corrected_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    idempotency_key        VARCHAR(100) NOT NULL,
    CONSTRAINT settlement_correction_type_check
        CHECK (correction_type IN ('AMOUNT_CORRECTION', 'SELLER_CORRECTION', 'PAYOUT_INVALIDATED')),
    CONSTRAINT settlement_correction_value_combination_check
        CHECK (
            (correction_type = 'AMOUNT_CORRECTION' AND corrected_amount IS NOT NULL AND corrected_amount > 0
                AND corrected_seller_id IS NULL)
            OR (correction_type = 'SELLER_CORRECTION' AND corrected_seller_id IS NOT NULL
                AND corrected_amount IS NULL)
            OR (correction_type = 'PAYOUT_INVALIDATED' AND corrected_amount IS NULL AND corrected_seller_id IS NULL)
        )
);

CREATE UNIQUE INDEX uk_settlement_correction_idempotency_key ON settlement_correction(idempotency_key);
CREATE INDEX idx_settlement_correction_settlement_id ON settlement_correction(settlement_id);
