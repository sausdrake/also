package com.example.autoauction.payment.domain;

/**
 * Slice 2 (Bank / Payment Infrastructure): the lifecycle of one deposit top-up {@link Payment}
 * intent. This enum represents ONLY the payment lifecycle.
 *
 * <p>Operational anomalies (amount mismatch, duplicate money receipt, a payment that never
 * reached a terminal state, a late provider success after expiry) are modelled separately by
 * {@link ReconciliationCase} and MUST NEVER appear here - there is deliberately no
 * {@code MISMATCH}, {@code DUPLICATE}, {@code STUCK} or {@code LATE_AFTER_EXPIRY} status value.
 */
public enum PaymentStatus {

    /** Intent created; no provider interaction has been recorded yet. */
    CREATED,

    /** Provider payment attempt is in flight. */
    PENDING,

    /** Authoritative provider confirmation received; the deposit credit happens on this transition, once. */
    SUCCEEDED,

    /** Provider declared the payment failed. Terminal. */
    FAILED,

    /** The intent lifetime elapsed with no success. Terminal for polling, but an authentic late
     *  provider success may still transition it to {@link #SUCCEEDED} exactly once. */
    EXPIRED,

    /** The user abandoned the intent before any provider attempt. Terminal. */
    CANCELLED
}
