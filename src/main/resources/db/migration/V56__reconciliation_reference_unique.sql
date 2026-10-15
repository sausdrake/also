-- F7: for an admin adjustment with reason RECONCILIATION, deposit_transactions.reference is the
-- external unique identifier of the money operation being booked (whatever the bank later calls
-- its transaction id - no bank format is assumed here). One external operation may be credited at
-- most once. The application checks this before writing (a repeat for the same user and amount
-- is a no-op, anything else a conflict); this index makes it hold for two attempts racing past
-- that check too.
--
-- Scope: only RECONCILIATION adjustments (adminAdjust stores the reason name in description).
-- References of other transaction types are not unique by design (e.g. every row of one auction
-- or withdrawal request shares its reference), so the uniqueness is deliberately partial.
--
-- Normalisation: one external operation is identified by btrim(reference) - " ABC " and "ABC"
-- are the same operation. The application trims before storing and looks up by the trimmed
-- value; the index below is on the same expression, and the pre-check groups by it too. NULL
-- references are no external operation at all: never duplicates of each other (excluded from
-- the check and the index alike).
DO $$
BEGIN
    IF EXISTS (
        SELECT btrim(reference)
        FROM deposit_transactions
        WHERE type = 'ADMIN_ADJUSTMENT' AND description = 'RECONCILIATION' AND reference IS NOT NULL
        GROUP BY btrim(reference)
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'V56: deposit_transactions already holds more than one RECONCILIATION credit for the same reference - '
            'resolve those duplicates manually before this migration can enforce one credit per external operation';
    END IF;
END $$;

CREATE UNIQUE INDEX uk_deposit_tx_reconciliation_reference
    ON deposit_transactions (btrim(reference))
    WHERE type = 'ADMIN_ADJUSTMENT' AND description = 'RECONCILIATION' AND reference IS NOT NULL;
