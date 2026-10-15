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
DO $$
BEGIN
    IF EXISTS (
        SELECT reference
        FROM deposit_transactions
        WHERE type = 'ADMIN_ADJUSTMENT' AND description = 'RECONCILIATION'
        GROUP BY reference
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'V56: deposit_transactions already holds more than one RECONCILIATION credit for the same reference - '
            'resolve those duplicates manually before this migration can enforce one credit per external operation';
    END IF;
END $$;

CREATE UNIQUE INDEX uk_deposit_tx_reconciliation_reference
    ON deposit_transactions(reference)
    WHERE type = 'ADMIN_ADJUSTMENT' AND description = 'RECONCILIATION';
