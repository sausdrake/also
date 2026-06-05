-- =====================================================
-- Медиафайлы аукциона + двухэтапный запуск
-- =====================================================
ALTER TABLE auctions
    ADD COLUMN IF NOT EXISTS main_photo_url        TEXT,
    ADD COLUMN IF NOT EXISTS additional_photo_urls TEXT,
    ADD COLUMN IF NOT EXISTS video_url             TEXT,
    ADD COLUMN IF NOT EXISTS confirmed_start       BOOLEAN   NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS confirmation_deadline TIMESTAMP;

-- Дефолтные настройки для новых полей system_settings
INSERT INTO system_settings (setting_key, setting_value, description) VALUES
    ('auction_confirm_hours',               '2',  'Часов до старта, за которые нужно подтвердить запуск аукциона'),
    ('completed_auction_visibility_hours',  '24', 'Часов после завершения аукциона, в течение которых он виден в ленте')
ON CONFLICT (setting_key) DO NOTHING;
