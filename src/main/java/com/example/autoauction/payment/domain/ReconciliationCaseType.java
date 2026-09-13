package com.example.autoauction.payment.domain;

/**
 * Slice 2: the operational anomaly a {@link ReconciliationCase} represents. Deliberately kept
 * entirely separate from {@link PaymentStatus} - an anomaly never becomes a payment-lifecycle
 * state.
 *
 * <ul>
 *   <li>{@code MISMATCH} - provider-confirmed amount or currency does not match the request
 *       (over- or under-payment; tolerance is zero). No automatic credit.</li>
 *   <li>{@code DUPLICATE} - a second real money receipt against an already-settled Payment, or
 *       a provider transaction claiming a Payment it does not own. No automatic credit.</li>
 *   <li>{@code STUCK} - a Payment still non-terminal past the stuck threshold.</li>
 *   <li>{@code LATE_AFTER_EXPIRY} - an authentic provider success arrived after the Payment
 *       had already expired; the credit is still applied exactly once and this records it.</li>
 * </ul>
 */
public enum ReconciliationCaseType {
    MISMATCH,
    DUPLICATE,
    STUCK,
    LATE_AFTER_EXPIRY
}
