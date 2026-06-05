-- Добавляем недостающие системные настройки
INSERT INTO system_settings (setting_key, setting_value, description) VALUES
('default_bid_step_percent', '1', 'Минимальный шаг ставки в процентах от текущей цены'),
('commission_percent',       '5', 'Комиссия платформы с продажи (%)'),
('diagnostic_deposit_amount','10000', 'Фиксированная сумма заморозки для заявки на диагностику (сом)')
ON CONFLICT (setting_key) DO NOTHING;

-- Если platform_fee_percent отсутствует — добавляем
INSERT INTO system_settings (setting_key, setting_value, description) VALUES
('platform_fee_percent', '5', 'Комиссия платформы (алиас commission_percent)')
ON CONFLICT (setting_key) DO NOTHING;

INSERT INTO system_settings (setting_key, setting_value, description) VALUES
('auction_confirm_hours', '24', 'Часов на подтверждение аукциона администратором'),
('completed_auction_visibility_hours', '720', 'Часов видимости завершённых аукционов')
ON CONFLICT (setting_key) DO NOTHING;
