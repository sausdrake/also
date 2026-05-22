-- Создаём таблицу failed_operations для H2 (тесты)
CREATE TABLE IF NOT EXISTS failed_operations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operation_type VARCHAR(50) NOT NULL,
    user_id BIGINT,
    auction_id BIGINT,
    error_message TEXT,
    stack_trace TEXT,
    retry_count INT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

-- Создаём индексы
CREATE INDEX IF NOT EXISTS idx_failed_operations_status ON failed_operations(status);
CREATE INDEX IF NOT EXISTS idx_failed_operations_created_at ON failed_operations(created_at);