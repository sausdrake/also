CREATE TABLE IF NOT EXISTS failed_operations (
    id BIGSERIAL PRIMARY KEY,
    operation_type VARCHAR(50) NOT NULL,
    user_id BIGINT,
    auction_id BIGINT,
    error_message TEXT,
    stack_trace TEXT,
    retry_count INT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_failed_operations_status ON failed_operations(status);
CREATE INDEX IF NOT EXISTS idx_failed_operations_created_at ON failed_operations(created_at);