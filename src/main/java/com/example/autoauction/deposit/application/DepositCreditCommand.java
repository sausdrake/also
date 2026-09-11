package com.example.autoauction.deposit.application;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Slice 2 · Step 2A: the neutral, deposit-owned value object through which a future payment
 * application layer requests an automatic deposit credit - WITHOUT the deposit module ever
 * importing anything from {@code payment.*}.
 *
 * <p>Locked dependency direction (Slice 2 Phase 2/3): {@code payment.application} constructs this
 * command from its own, already-validated {@code Payment} aggregate at exactly one call site and
 * passes it into {@link DepositService#creditFromPayment(DepositCreditCommand)}. This type
 * deliberately carries none of Payment's own facts (no paymentId, no providerTransactionId, no
 * currency, no admin actor, no transaction type) - only the four primitives a credit actually
 * needs. Credit-only: {@code amount} must be strictly positive; this command has no debit path.
 */
public record DepositCreditCommand(Long userId, BigDecimal amount, String ledgerReference,
                                    String ledgerIdempotencyKey) {

    public DepositCreditCommand {
        Objects.requireNonNull(userId, "userId не может быть null");
        Objects.requireNonNull(amount, "amount не может быть null");
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount должен быть больше 0");
        }
        if (ledgerReference == null || ledgerReference.isBlank()) {
            throw new IllegalArgumentException("ledgerReference не может быть пустым");
        }
        if (ledgerIdempotencyKey == null || ledgerIdempotencyKey.isBlank()) {
            throw new IllegalArgumentException("ledgerIdempotencyKey не может быть пустым");
        }
    }
}
