-- Таблица deposit_frozen
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

-- Индексы с проверкой существования
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_deposit_frozen_auction_id') THEN
        CREATE INDEX idx_deposit_frozen_auction_id ON deposit_frozen(auction_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_deposit_frozen_deposit_id') THEN
        CREATE INDEX idx_deposit_frozen_deposit_id ON deposit_frozen(deposit_id);
    END IF;
END $$;