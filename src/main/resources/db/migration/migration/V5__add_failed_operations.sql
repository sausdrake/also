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

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_failed_operations_status') THEN
        CREATE INDEX idx_failed_operations_status ON failed_operations(status);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_failed_operations_created_at') THEN
        CREATE INDEX idx_failed_operations_created_at ON failed_operations(created_at);
    END IF;
END $$;