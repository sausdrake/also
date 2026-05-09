-- src/main/resources/db/migration/V4__add_deposit_frozen_table.sql
CREATE TABLE IF NOT EXISTS deposit_frozen (
    id BIGSERIAL PRIMARY KEY,
    deposit_id BIGINT NOT NULL REFERENCES deposits(id) ON DELETE CASCADE,
    auction_id BIGINT NOT NULL REFERENCES auctions(id) ON DELETE CASCADE,
    amount DECIMAL(19,2) NOT NULL,
    freeze_reason VARCHAR(50) NOT NULL,
    is_winner BOOLEAN NOT NULL DEFAULT FALSE,
    frozen_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT uk_deposit_auction UNIQUE (deposit_id, auction_id)
);

CREATE INDEX idx_deposit_frozen_auction_id ON deposit_frozen(auction_id);
CREATE INDEX idx_deposit_frozen_deposit_id ON deposit_frozen(deposit_id);