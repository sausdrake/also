-- =====================================================
-- Системные настройки
-- =====================================================
CREATE TABLE IF NOT EXISTS system_settings (
    id BIGSERIAL PRIMARY KEY,
    setting_key VARCHAR(100) NOT NULL UNIQUE,
    setting_value TEXT,
    description TEXT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Вставка начальных настроек
INSERT INTO system_settings (setting_key, setting_value, description) VALUES
('deposit_percent', '1', 'Процент от стартовой цены для заморозки депозита (1-10)'),
('default_bid_step_percent', '1', 'Шаг ставки в процентах от стартовой цены'),
('commission_percent', '5', 'Комиссия с продажи для площадки')
ON CONFLICT (setting_key) DO NOTHING;

-- =====================================================
-- Заявки на диагностику
-- =====================================================
CREATE TABLE IF NOT EXISTS diagnostic_requests (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    brand VARCHAR(100) NOT NULL,
    model VARCHAR(100) NOT NULL,
    year INTEGER NOT NULL,
    vin VARCHAR(17) NOT NULL,
    deposit_frozen DECIMAL(19,2) NOT NULL,
    status VARCHAR(50) DEFAULT 'PENDING',
    assigned_to BIGINT REFERENCES users(id),
    rejected_reason TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_diagnostic_requests_user_id ON diagnostic_requests(user_id);
CREATE INDEX idx_diagnostic_requests_status ON diagnostic_requests(status);
CREATE INDEX idx_diagnostic_requests_vin ON diagnostic_requests(vin);

-- =====================================================
-- Шаблоны анкет
-- =====================================================
CREATE TABLE IF NOT EXISTS inspection_templates (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    vehicle_type VARCHAR(50),
    description TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    created_by BIGINT REFERENCES users(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================
-- Группы пунктов в шаблоне
-- =====================================================
CREATE TABLE IF NOT EXISTS inspection_groups (
    id BIGSERIAL PRIMARY KEY,
    template_id BIGINT NOT NULL REFERENCES inspection_templates(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    sort_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================
-- Пункты анкеты
-- =====================================================
CREATE TABLE IF NOT EXISTS inspection_items (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES inspection_groups(id) ON DELETE CASCADE,
    title VARCHAR(500) NOT NULL,
    description TEXT,
    has_photo BOOLEAN DEFAULT FALSE,
    has_video BOOLEAN DEFAULT FALSE,
    has_rating BOOLEAN DEFAULT FALSE,
    is_required BOOLEAN DEFAULT TRUE,
    sort_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================
-- Ответы диагноста
-- =====================================================
CREATE TABLE IF NOT EXISTS inspection_answers (
    id BIGSERIAL PRIMARY KEY,
    diagnostic_request_id BIGINT NOT NULL REFERENCES diagnostic_requests(id) ON DELETE CASCADE,
    inspection_item_id BIGINT NOT NULL REFERENCES inspection_items(id),
    rating INT CHECK (rating >= 1 AND rating <= 5),
    comment TEXT,
    photo_urls TEXT[],
    video_urls TEXT[],
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- =====================================================
-- Связь одобренного отчёта с автомобилем
-- =====================================================
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS diagnostic_request_id BIGINT REFERENCES diagnostic_requests(id);