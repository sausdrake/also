-- MEDIA-3.4: tracks when a DiagnosticMedia row was claimed for video
-- processing (PENDING -> PROCESSING). Nullable, no default, no backfill -
-- existing rows have never been claimed. Written ONLY by the atomic
-- PENDING -> PROCESSING claim transition; never treated as a generic
-- "last updated" timestamp (that role stays with updated_at). Read only by
-- the stale-PROCESSING reconciliation sweep, and only while
-- processing_status = 'PROCESSING'.
ALTER TABLE diagnostic_media
    ADD COLUMN processing_claimed_at TIMESTAMP(6) WITH TIME ZONE NULL;
