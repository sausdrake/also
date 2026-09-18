-- Phase 38 (initiation crash-window partial mitigation - forensic/observability only): additive
-- only. Adds exactly one new allowed value, 'INITIATION_ATTEMPTED', to payment_event's existing
-- type CHECK constraint. No existing row, column, index or other constraint is touched. No
-- existing payment_event data is modified.
--
-- 'INITIATION_ATTEMPTED' means only: the application durably recorded that it is about to invoke
-- PaymentProviderPort.initiatePayment() for this payment. It does NOT mean the provider received
-- or accepted the request, that a providerReference exists, or that the Payment can transition to
-- PENDING. It is never consumed by PaymentInitiationRecoveryScheduler or any other recovery
-- mechanism - only INITIATION_ACCEPTED evidence remains recovery-authoritative.

ALTER TABLE payment_event DROP CONSTRAINT payment_event_type_check;

ALTER TABLE payment_event ADD CONSTRAINT payment_event_type_check
    CHECK (type IN ('CREATED', 'PENDING', 'SUCCEEDED', 'FAILED', 'EXPIRED', 'CANCELLED',
                    'CALLBACK_RECEIVED', 'POLL_RESULT', 'RECONCILIATION', 'INITIATION_ACCEPTED',
                    'INITIATION_ATTEMPTED'));
