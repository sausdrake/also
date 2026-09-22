package com.example.autoauction.deposit.domain;

/**
 * Thrown by {@code DepositService.creditFromPayment} when an automatic credit's ledger
 * idempotency key already belongs to a different, non-matching ledger entry (different user,
 * amount, reference, or type) - i.e. two distinct financial events are claiming the same
 * idempotency key. Deposit-domain only, no dependency on payment.* - a payment.* caller that
 * needs to react to this must catch it explicitly and translate it into whatever
 * payment-lifecycle handling it needs; this exception itself carries no payment-domain concept.
 *
 * <p>Deliberately a subclass of {@link IllegalStateException}, not a direct {@link
 * RuntimeException} - every other idempotency/fingerprint-conflict throw site in this class
 * ({@code deposit}, {@code withdraw}, {@code adminAdjust}) already throws a plain {@code
 * IllegalStateException} for the same family of "conflicting fingerprint for this key" condition,
 * and existing tests for those call sites assert exactly that supertype. Remaining a subtype keeps
 * this a genuinely distinct, specifically-catchable type for a caller that wants to react to THIS
 * one condition (e.g. {@code PaymentConfirmationOrchestrator}), while every existing {@code
 * IllegalStateException}-based check elsewhere in this module keeps working unchanged.
 */
public class LedgerIdempotencyConflictException extends IllegalStateException {
    private final String ledgerIdempotencyKey;

    public LedgerIdempotencyConflictException(String ledgerIdempotencyKey, String message) {
        super(message);
        this.ledgerIdempotencyKey = ledgerIdempotencyKey;
    }

    public String getLedgerIdempotencyKey() {
        return ledgerIdempotencyKey;
    }
}
