-- Slice 1B: payment/handover deadline for a WAITING_FOR_PAYMENT auction, plus its append-only
-- change history. Nullable at the DB schema level for historical rows (auctions that reached
-- WAITING_FOR_PAYMENT before this column existed) - no deadline is invented for them, per the
-- locked migration boundary. change_type CHECK mirrors settlement_correction_type_check's (V44)
-- established "VARCHAR + CHECK enumeration" convention.

ALTER TABLE auctions ADD COLUMN payment_deadline TIMESTAMP WITH TIME ZONE;

CREATE TABLE payment_deadline_history (
    id                     BIGSERIAL PRIMARY KEY,
    auction_id             BIGINT NOT NULL REFERENCES auctions(id),
    change_type            VARCHAR(24) NOT NULL,
    previous_deadline      TIMESTAMP WITH TIME ZONE,
    new_deadline           TIMESTAMP WITH TIME ZONE NOT NULL,
    reason                 TEXT NOT NULL,
    changed_by_admin_id    BIGINT,
    changed_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT payment_deadline_history_change_type_check
        CHECK (change_type IN ('INITIAL', 'ADMIN_EXTENSION')),
    CONSTRAINT payment_deadline_history_actor_check
        CHECK (
            (change_type = 'INITIAL' AND changed_by_admin_id IS NULL AND previous_deadline IS NULL)
            OR (change_type = 'ADMIN_EXTENSION' AND changed_by_admin_id IS NOT NULL AND previous_deadline IS NOT NULL)
        )
);

CREATE INDEX idx_payment_deadline_history_auction_id ON payment_deadline_history(auction_id);
