package com.example.autoauction.payment.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Slice 2: the locked deposit top-up amount policy (KGS). A pure, stateless policy - it performs
 * no persistence and no querying; the caller supplies the already-computed rolling-24h total.
 *
 * <p>Locked values (Phase 2 Corrective Business Semantics Lock):
 * <ul>
 *   <li>{@code MIN_TOP_UP = 100}</li>
 *   <li>{@code MAX_TOP_UP = 200_000}</li>
 *   <li>{@code DAILY_LIMIT = 1_000_000} per rolling 24h per user</li>
 *   <li>no monthly limit</li>
 *   <li>{@code OVERPAYMENT_TOLERANCE = 0} - the provider-confirmed amount must equal the
 *       requested amount exactly to be eligible for automatic credit</li>
 * </ul>
 * Provider-specific limits are NOT represented here (a provider does not exist yet in Phase 3A).
 * There are no fees in this policy.
 */
public final class PaymentAmountPolicy {

    public static final BigDecimal MIN_TOP_UP = new BigDecimal("100");
    public static final BigDecimal MAX_TOP_UP = new BigDecimal("200000");
    public static final BigDecimal DAILY_LIMIT = new BigDecimal("1000000");
    public static final BigDecimal OVERPAYMENT_TOLERANCE = BigDecimal.ZERO;

    private PaymentAmountPolicy() {
    }

    /**
     * @throws IllegalArgumentException if {@code amount} is null, below {@link #MIN_TOP_UP},
     *         above {@link #MAX_TOP_UP}, or has more than two decimal places
     */
    public static void validateAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "Сумма пополнения обязательна");
        if (amount.compareTo(MIN_TOP_UP) < 0) {
            throw new IllegalArgumentException(
                    "Минимальная сумма пополнения — " + MIN_TOP_UP.toPlainString() + " KGS");
        }
        if (amount.compareTo(MAX_TOP_UP) > 0) {
            throw new IllegalArgumentException(
                    "Максимальная сумма одного пополнения — " + MAX_TOP_UP.toPlainString() + " KGS");
        }
        // S11: amount_requested is NUMERIC(19,2) - more precision would be rounded silently.
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Сумма пополнения не может содержать больше двух знаков после запятой");
        }
    }

    /**
     * @param rollingWindowTotal the sum of the user's successful top-ups in the trailing 24 hours
     * @param newAmount          the amount the user now wants to add
     * @throws IllegalArgumentException if {@code rollingWindowTotal + newAmount} exceeds
     *         {@link #DAILY_LIMIT}
     */
    public static void validateDailyLimit(BigDecimal rollingWindowTotal, BigDecimal newAmount) {
        Objects.requireNonNull(rollingWindowTotal, "rollingWindowTotal не может быть null");
        Objects.requireNonNull(newAmount, "newAmount не может быть null");
        if (rollingWindowTotal.add(newAmount).compareTo(DAILY_LIMIT) > 0) {
            throw new IllegalArgumentException(
                    "Превышен суточный лимит пополнения (" + DAILY_LIMIT.toPlainString() + " KGS за 24 часа)");
        }
    }

    /**
     * The zero-tolerance amount-equality rule for automatic credit eligibility.
     *
     * @return {@code true} iff {@code confirmed} equals {@code requested} exactly
     */
    public static boolean amountsMatch(BigDecimal requested, BigDecimal confirmed) {
        if (requested == null || confirmed == null) {
            return false;
        }
        return confirmed.subtract(requested).abs().compareTo(OVERPAYMENT_TOLERANCE) <= 0;
    }
}
