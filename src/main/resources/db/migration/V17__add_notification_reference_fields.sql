ALTER TABLE user_notifications
    ADD COLUMN IF NOT EXISTS reference_id   BIGINT       NULL,
    ADD COLUMN IF NOT EXISTS reference_type VARCHAR(50)  NULL;
