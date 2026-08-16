-- MEDIA-3.2: diagnostic photo/video media, scoped by (diagnostic_request_id,
-- inspection_item_id). Independent of inspection_answers - no FK to it, and no
-- template_id column (the request's template is already authoritative via
-- diagnostic_requests.template_id, MEDIA-3.1A). Independent of media_assets
-- (common/media) - a separate, generic, non-diagnostic subsystem left
-- untouched.
CREATE TABLE diagnostic_media (
    id                     BIGSERIAL PRIMARY KEY,
    diagnostic_request_id  BIGINT NOT NULL REFERENCES diagnostic_requests(id),
    inspection_item_id     BIGINT NOT NULL REFERENCES inspection_items(id),
    uploader_id            BIGINT NOT NULL REFERENCES users(id),

    storage_key            VARCHAR(500) NOT NULL UNIQUE,

    media_type             VARCHAR(20) NOT NULL,
    mime_type               VARCHAR(100),
    size_bytes              BIGINT,

    sort_order              INT NOT NULL DEFAULT 0,
    caption                 VARCHAR(1000),

    upload_status           VARCHAR(20) NOT NULL,
    processing_status       VARCHAR(20) NOT NULL,

    duration_seconds        INT,
    width                   INT,
    height                  INT,
    thumbnail_storage_key   VARCHAR(500),

    created_at              TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Primary read path: list/order media for one (request, item).
CREATE INDEX idx_diagnostic_media_request_item ON diagnostic_media(diagnostic_request_id, inspection_item_id);

-- Reconciliation sweep: find PENDING_UPLOAD candidates by age.
CREATE INDEX idx_diagnostic_media_status_created ON diagnostic_media(upload_status, created_at);
