package com.example.autoauction.payment.domain;

/**
 * Slice 2: the kind of thing a {@link PaymentEvent} records. Lifecycle-transition types mirror
 * {@link PaymentStatus} values; the remaining three record inputs/actions that are not
 * themselves a status change (a raw provider callback arriving, a status-poll result, a
 * reconciliation action).
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
    RECONCILIATION
}
