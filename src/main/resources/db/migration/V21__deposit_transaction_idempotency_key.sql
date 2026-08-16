-- D-1: idempotency key for deposit_transactions to prevent duplicate-request double-crediting.
--
-- The existing `reference` column cannot become globally unique as-is: all 30 existing
-- DEPOSIT-type rows share the literal string "DEPOSIT", and BLOCK/UNBLOCK pairs for the
-- same auction legitimately reuse the same auction-id reference value. A new, dedicated
-- column is required instead of repurposing `reference`.
--
-- Nullable by design: only DepositService.adminAdjust sets this value (optionally, for
-- backward compatibility with the existing admin UI which does not yet supply one). All
-- other transaction types (DEPOSIT/WITHDRAWAL/BLOCK/UNBLOCK/CHARGE) and all 105 existing
-- historical rows are unaffected and remain NULL. A partial unique index enforces
-- uniqueness only where a key is actually supplied, so NULLs never collide.
ALTER TABLE deposit_transactions ADD COLUMN idempotency_key VARCHAR(100);

CREATE UNIQUE INDEX uk_deposit_transactions_idempotency_key
    ON deposit_transactions(idempotency_key)
    WHERE idempotency_key IS NOT NULL;
