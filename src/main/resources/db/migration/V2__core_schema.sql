-- Core schema reconstruction: recreates the six tables that were never
-- represented by any Flyway migration (vehicles, auctions, deposits,
-- deposit_transactions, auction_participants, refresh_tokens) but are
-- required by V3 onward. Sourced directly from a schema-only dump of the
-- authoritative database, not from JPA entities.
--
-- Uses IF NOT EXISTS throughout so this migration is a safe no-op against a
-- database where these tables already exist out-of-band (via ddl-auto),
-- while still creating them for real on a fresh install.
--
-- Deliberately excluded here (owned by the migration that already adds them):
--   vehicles.diagnostic_request_id -> V9
--   auctions.was_extended          -> V6
--   auctions.extension_count       -> V7
--   auctions.main_photo_url        -> V12
--   auctions.additional_photo_urls -> V12
--   auctions.video_url             -> V12
--   auctions.confirmed_start       -> V12
--   auctions.confirmation_deadline -> V12
--
-- vehicles.year is used here (not production_year) since V3 renames it
-- immediately after this migration.

CREATE TABLE IF NOT EXISTS vehicles (
    id BIGSERIAL PRIMARY KEY,
    admin_id BIGINT,
    admin_name VARCHAR(255),
    brand VARCHAR(50) NOT NULL,
    color VARCHAR(30),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    description TEXT,
    diagnostic_id BIGINT NOT NULL,
    diagnostic_name VARCHAR(255),
    engine_capacity DOUBLE PRECISION,
    fuel_type VARCHAR(255),
    license_plate VARCHAR(20),
    mileage INTEGER,
    model VARCHAR(50) NOT NULL,
    rejection_reason TEXT,
    reviewed_at TIMESTAMP(6) WITH TIME ZONE,
    starting_price NUMERIC(10,2),
    status VARCHAR(255) NOT NULL,
    submitted_at TIMESTAMP(6) WITH TIME ZONE,
    transmission VARCHAR(30),
    type VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    vin VARCHAR(17) NOT NULL,
    year INTEGER NOT NULL,
    CONSTRAINT uk_6brka0b8j7n06xd43x37hlvlt UNIQUE (vin),
    CONSTRAINT vehicles_fuel_type_check CHECK ((fuel_type)::text = ANY ((ARRAY['PETROL','DIESEL','ELECTRIC','HYBRID','LPG','CNG'])::text[])),
    CONSTRAINT vehicles_status_check CHECK ((status)::text = ANY ((ARRAY['DRAFT','PENDING_REVIEW','NEEDS_FIXES','APPROVED','IN_AUCTION','SOLD','WITHDRAWN'])::text[])),
    CONSTRAINT vehicles_type_check CHECK ((type)::text = ANY ((ARRAY['SEDAN','SUV','HATCHBACK','COUPE','CONVERTIBLE','WAGON','TRUCK','VAN','MOTORCYCLE','OTHER'])::text[]))
);

CREATE TABLE IF NOT EXISTS auctions (
    id BIGSERIAL PRIMARY KEY,
    buy_now_price NUMERIC(10,2),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_by BIGINT NOT NULL,
    current_price NUMERIC(10,2) NOT NULL,
    end_time TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    min_bid_step NUMERIC(10,2) NOT NULL,
    reserve_price NUMERIC(10,2),
    start_time TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    starting_price NUMERIC(10,2) NOT NULL,
    status VARCHAR(255) NOT NULL,
    total_bids INTEGER NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    vehicle_id BIGINT NOT NULL,
    vehicle_info TEXT,
    winner_id BIGINT,
    winning_bid NUMERIC(10,2),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT auctions_status_check CHECK ((status)::text = ANY ((ARRAY['CREATED','ACTIVE','COMPLETED','SOLD','EXPIRED','CANCELLED'])::text[]))
);

COMMENT ON COLUMN auctions.version IS 'Версия для оптимистичной блокировки';

CREATE TABLE IF NOT EXISTS deposits (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    balance NUMERIC(19,2) NOT NULL DEFAULT 0,
    blocked_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT deposits_user_id_key UNIQUE (user_id)
);

CREATE INDEX IF NOT EXISTS idx_deposits_user_id ON deposits(user_id);

CREATE TABLE IF NOT EXISTS deposit_transactions (
    id BIGSERIAL PRIMARY KEY,
    deposit_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    auction_id BIGINT,
    type VARCHAR(20) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    balance_before NUMERIC(19,2) NOT NULL,
    balance_after NUMERIC(19,2) NOT NULL,
    reference VARCHAR(100),
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_deposit_transactions_deposit_id ON deposit_transactions(deposit_id);
CREATE INDEX IF NOT EXISTS idx_deposit_transactions_user_id ON deposit_transactions(user_id);
CREATE INDEX IF NOT EXISTS idx_deposit_transactions_auction_id ON deposit_transactions(auction_id);

CREATE TABLE IF NOT EXISTS auction_participants (
    id BIGSERIAL PRIMARY KEY,
    auction_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    frozen_amount NUMERIC(19,2) NOT NULL,
    is_winner BOOLEAN DEFAULT FALSE,
    joined_at TIMESTAMP DEFAULT NOW(),
    CONSTRAINT auction_participants_auction_id_user_id_key UNIQUE (auction_id, user_id),
    CONSTRAINT auction_participants_auction_id_fkey FOREIGN KEY (auction_id) REFERENCES auctions(id) ON DELETE CASCADE,
    CONSTRAINT auction_participants_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Plain CREATE INDEX IF NOT EXISTS is not safe here: PostgreSQL checks that the
-- connecting role owns auction_participants before it ever evaluates whether the
-- index already exists, so IF NOT EXISTS cannot suppress an ownership error on a
-- table this migration doesn't own on the authoritative database (it is owned by
-- a different role there). Pre-checking pg_indexes avoids issuing CREATE INDEX at
-- all when the index is already present, so the ownership check is never reached.
-- current_schema() keeps this check aligned with whatever schema Flyway is
-- actually targeting, rather than assuming a fixed schema name.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE schemaname = current_schema()
          AND tablename = 'auction_participants'
          AND indexname = 'idx_auction_participants_auction_id'
    ) THEN
        CREATE INDEX idx_auction_participants_auction_id ON auction_participants(auction_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE schemaname = current_schema()
          AND tablename = 'auction_participants'
          AND indexname = 'idx_auction_participants_user_id'
    ) THEN
        CREATE INDEX idx_auction_participants_user_id ON auction_participants(user_id);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expiry_date TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    revoked BOOLEAN NOT NULL,
    token VARCHAR(500) NOT NULL,
    user_id BIGINT NOT NULL,
    CONSTRAINT uk_ghpmfn23vmxfu3spu3lfg4r2d UNIQUE (token),
    CONSTRAINT fk1lih5y2npsf8u5o3vhdb9y0os FOREIGN KEY (user_id) REFERENCES users(id)
);
