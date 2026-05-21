-- Добавляем колонку extension_count в таблицу auctions
ALTER TABLE auctions ADD COLUMN IF NOT EXISTS extension_count INTEGER NOT NULL DEFAULT 0;

-- Комментарий для документации
COMMENT ON COLUMN auctions.extension_count IS 'Количество продлений аукциона';

-- Обновляем существующие строки (на всякий случай)
UPDATE auctions SET extension_count = 0 WHERE extension_count IS NULL;