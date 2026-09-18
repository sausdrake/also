-- Phase 33 (Provider-reference durability - local recovery half): additive only. Adds exactly
-- one new allowed value, 'INITIATION_ACCEPTED', to payment_event's existing type CHECK
-- constraint. No existing row, column, index or other constraint is touched. No existing
-- payment_event data is modified.
--
-- 'INITIATION_ACCEPTED' means only: the application received a successful
-- PaymentProviderPort.initiatePayment() response containing a usable providerReference, durably
-- recorded before the existing CREATED -> PENDING transition (finalizeAccepted) is attempted, so
-- a process crash or transaction failure in that window does not lose the reference. It is not a
-- Payment lifecycle status and never appears as a PaymentStatus value.

ALTER TABLE payment_event DROP CONSTRAINT payment_event_type_check;

ALTER TABLE payment_event ADD CONSTRAINT payment_event_type_check
    CHECK (type IN ('CREATED', 'PENDING', 'SUCCEEDED', 'FAILED', 'EXPIRED', 'CANCELLED',
                    'CALLBACK_RECEIVED', 'POLL_RESULT', 'RECONCILIATION', 'INITIATION_ACCEPTED'));
