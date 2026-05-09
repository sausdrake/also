-- src/main/resources/db/migration/V7__add_extension_count_column.sql
ALTER TABLE auctions ADD COLUMN IF NOT EXISTS extension_count INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN auctions.extension_count IS 'Количество продлений аукциона';