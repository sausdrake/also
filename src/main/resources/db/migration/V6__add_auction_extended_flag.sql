-- src/main/resources/db/migration/V6__add_auction_extended_flag.sql
ALTER TABLE auctions ADD COLUMN was_extended BOOLEAN NOT NULL DEFAULT FALSE;