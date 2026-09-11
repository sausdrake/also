-- SLICE 2 (Bank / Payment Infrastructure) - Step 2B: Payment creation foundation.
--
-- Purely additive: adds exactly one nullable column to `payment`. No other table, column, row,
-- constraint, or index from V1-V42 is touched.
--
-- provider_reference identifies the provider-side payment/session/order reference returned when
-- the provider accepts a creation request - available before a final provider_transaction_id
-- exists (which is assigned only on confirmed SUCCEEDED, by markSucceeded). It is independent
-- from provider_transaction_id: never required to equal it, never a replacement for it, and is
-- NOT the financial settlement identifier.
--
-- Length VARCHAR(128): matches the existing provider_transaction_id convention (V42) - the
-- closest existing analogue, another provider-supplied opaque string identifier on this row.
--
-- No UNIQUE constraint: provider_reference is not globally unique by any locked business rule -
-- a provider may scope references by merchant/session, unlike merchant_reference (server-
-- generated dedup key), provider_transaction_id (financial settlement dedup key), or
-- creation_idempotency_key (client-retry dedup key), none of which apply here.
--
-- No index: no Step 2B query looks up a Payment by this column; a future lookup belongs to the
-- confirmation/reconciliation step that will actually need it.

ALTER TABLE payment
    ADD COLUMN provider_reference VARCHAR(128);
