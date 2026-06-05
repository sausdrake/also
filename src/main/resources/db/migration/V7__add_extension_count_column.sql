DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'auctions' AND column_name = 'extension_count') THEN
        ALTER TABLE auctions ADD COLUMN extension_count INT NOT NULL DEFAULT 0;
    END IF;
END $$;