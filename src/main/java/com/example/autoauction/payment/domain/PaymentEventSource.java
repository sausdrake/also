package com.example.autoauction.payment.domain;

/**
 * Slice 2: who/what produced a {@link PaymentEvent}. Used for audit and for distinguishing a
 * client-initiated action from an inbound provider callback from a server-side poll from a
 * scheduled reconciliation.
 */
public enum PaymentEventSource {
    CLIENT,
    PROVIDER_WEBHOOK,
    PROVIDER_POLL,
    ADMIN,
    SCHEDULER
}
