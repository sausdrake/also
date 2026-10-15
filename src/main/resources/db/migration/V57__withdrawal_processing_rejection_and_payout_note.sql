-- F6.1: completes the manual payout request lifecycle.
--
-- 1. PROCESSING -> REJECTED becomes possible (only with the admin's explicit confirmation that no
--    payout was made - enforced by the application; nothing in the database can prove it). A
--    request rejected from PROCESSING keeps processing_started_at/by as evidence that it had been
--    taken into work, so for REJECTED those two columns are now either both NULL (rejected from
--    PENDING) or both set (rejected from PROCESSING) - never half-filled.
-- 2. PAID requires payout_note: the admin's note on how the manual payout was made (never full
--    card or account numbers - the UI says so). It exists only on PAID, and it must contain at
--    least one visible character: the same class of invisible characters the domain removes
--    (ASCII whitespace, NBSP, Unicode spaces, zero-width characters) is removed here before the
--    emptiness test.
--
-- Money is untouched: PAID still moves nothing, REJECTED still returns the amount through the
-- single WITHDRAWAL_RETURN:<id> ledger row (V21 unique key). Every other state rule of V55 is
-- carried over unchanged.
ALTER TABLE withdrawal_requests ADD COLUMN payout_note VARCHAR(1000);

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
        AND payout_note IS NULL
        AND ((processing_started_at IS NULL AND processing_started_by IS NULL)
             OR (processing_started_at IS NOT NULL AND processing_started_by IS NOT NULL)))
);
