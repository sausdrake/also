-- MEDIA-HARDENING-1: tracks the R2 key of a thumbnail that has been (or is
-- about to be) uploaded but not yet linked into thumbnail_storage_key by a
-- successful finalizeProcessingReady() commit. Nullable, no default, no
-- backfill - existing rows have never had a pending thumbnail attempt.
-- Written ONLY by the atomic PROCESSING declare-intent write, immediately
-- before the R2 upload begins; cleared ONLY by (1) the same atomic UPDATE
-- that promotes it into thumbnail_storage_key on success, (2) a successful
-- compensating delete on an exception path, or (3) a successful orphan
-- reconciliation delete after the row has left PROCESSING. This is what
-- lets a crashed processing attempt (no exception, no compensating delete
-- ever ran) still be discovered and cleaned up after stale-PROCESSING
-- recovery, instead of leaving an untracked, permanently orphaned R2 object.
ALTER TABLE diagnostic_media
    ADD COLUMN pending_thumbnail_storage_key VARCHAR(500) NULL;
