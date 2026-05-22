DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'auctions' AND column_name = 'was_extended') THEN
        ALTER TABLE auctions ADD COLUMN was_extended BOOLEAN NOT NULL DEFAULT FALSE;
    END IF;
END $$;