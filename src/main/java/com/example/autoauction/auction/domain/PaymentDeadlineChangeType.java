package com.example.autoauction.auction.domain;

/**
 * Slice 1B: discriminates who/what produced a {@link PaymentDeadlineHistory} row.
 */
public enum PaymentDeadlineChangeType {
    /** System-computed, created atomically with the WAITING_FOR_PAYMENT transition. */
    INITIAL,
    /** Admin-driven override/extension, made while the auction is WAITING_FOR_PAYMENT. */
    ADMIN_EXTENSION
}
