-- WD-01: a rejection reason is shown to the user, so - like the payout note (O2, V57) - it must
-- contain at least one visible character. Same character class as V57 and as the domain
-- (WithdrawalRequest.INVISIBLE_CHARS). Everything else in the state/audit CHECK is unchanged.
-- On a database that already holds a REJECTED request with a visually empty reason this migration
-- fails as a whole and changes nothing (see DB-02: read-only check of old data first).
ALTER TABLE withdrawal_requests DROP CONSTRAINT withdrawal_requests_state_audit_check;

ALTER TABLE withdrawal_requests ADD CONSTRAINT withdrawal_requests_state_audit_check CHECK (
    (status = 'PENDING'
        AND processing_started_at IS NULL AND processing_started_by IS NULL
        AND processed_at IS NULL AND processed_by IS NULL AND rejection_reason IS NULL
        AND payout_note IS NULL)
    OR (status = 'PROCESSING'
        AND processing_started_at IS NOT NULL AND processing_started_by IS NOT NULL
        AND processed_at IS NULL AND processed_by IS NULL AND rejection_reason IS NULL
        AND payout_note IS NULL)
    OR (status = 'PAID'
        AND processing_started_at IS NOT NULL AND processing_started_by IS NOT NULL
        AND processed_at IS NOT NULL AND processed_by IS NOT NULL AND rejection_reason IS NULL
        AND payout_note IS NOT NULL
        AND regexp_replace(payout_note,
                '[\t\n\v\f\r \u0085\u00A0\u1680\u2000-\u200B\u2028\u2029\u202F\u205F\u3000\u2060\uFEFF]', '', 'g') <> '')
    OR (status = 'REJECTED'
        AND processed_at IS NOT NULL AND processed_by IS NOT NULL AND rejection_reason IS NOT NULL
        AND regexp_replace(rejection_reason,
                '[\t\n\v\f\r \u0085\u00A0\u1680\u2000-\u200B\u2028\u2029\u202F\u205F\u3000\u2060\uFEFF]', '', 'g') <> ''
        AND payout_note IS NULL
        AND ((processing_started_at IS NULL AND processing_started_by IS NULL)
             OR (processing_started_at IS NOT NULL AND processing_started_by IS NOT NULL)))
);
