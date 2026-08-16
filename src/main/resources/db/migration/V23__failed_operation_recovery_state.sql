-- R-2: adds the minimum fields needed for automatic recovery of failed financial
-- operations (UNFREEZE_AUCTION, MARK_WINNER, UNFREEZE_DIAGNOSTIC_REQUEST).
--
-- next_retry_at: drives backoff scheduling - the recovery job only picks up a PENDING
-- row once next_retry_at is null or has passed. Without this, a permanently-failing
-- operation would be retried every scheduler tick indefinitely.
--
-- resolved_at: when a failed operation is confirmed resolved (either because the retry
-- succeeded, or because the underlying state was found to already be correct), this
-- records when - required for admin visibility (distinguishing "still open" from
-- "resolved, and when").
--
-- Both nullable, no default: existing rows (status='PENDING' already, from before this
-- migration) are unaffected - the recovery job treats a NULL next_retry_at as
-- "eligible immediately", so pre-existing PENDING rows become retryable on the next
-- scheduler tick, which is the correct behavior (they were never retried before this
-- feature existed).
ALTER TABLE failed_operations ADD COLUMN next_retry_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE failed_operations ADD COLUMN resolved_at TIMESTAMP WITH TIME ZONE;
