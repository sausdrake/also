package com.example.autoauction.payment.domain;

/**
 * Slice 2: the kind of thing a {@link PaymentEvent} records. Lifecycle-transition types mirror
 * {@link PaymentStatus} values; the remaining types record inputs/actions that are not
 * themselves a status change (a raw provider callback arriving, a status-poll result, a
 * reconciliation action, a durably-recorded provider initiation acceptance).
 *
 * <p>{@code INITIATION_ACCEPTED} (Phase 33) means only: the application received a successful
 * {@code PaymentProviderPort#initiatePayment} response containing a usable providerReference. It
 * does NOT mean the payment succeeded, that a provider transaction completed, that a deposit was
 * credited, or that the Payment became {@link PaymentStatus#PENDING} - {@code PENDING} remains
 * the sole authoritative record of that local state transition.
 */
public enum PaymentEventType {
    CREATED,
    PENDING,
    SUCCEEDED,
    FAILED,
    EXPIRED,
    CANCELLED,
    CALLBACK_RECEIVED,
    POLL_RESULT,
    RECONCILIATION,
    INITIATION_ACCEPTED
}
