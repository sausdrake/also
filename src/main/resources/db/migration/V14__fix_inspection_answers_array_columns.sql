-- Convert inspection_answers array columns from text[] to text (JSON storage).
-- Compatible with H2 and PostgreSQL. Existing NULL values remain NULL.
ALTER TABLE inspection_answers
    ALTER COLUMN photo_urls TYPE TEXT USING COALESCE(array_to_json(photo_urls)::text, '[]'),
    ALTER COLUMN video_urls TYPE TEXT USING COALESCE(array_to_json(video_urls)::text, '[]');
