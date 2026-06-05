-- Fix: additional_photo_urls was created as text[] instead of text.
-- All existing values are NULL so no data conversion is needed.
ALTER TABLE auctions
    ALTER COLUMN additional_photo_urls TYPE TEXT USING additional_photo_urls::text;
