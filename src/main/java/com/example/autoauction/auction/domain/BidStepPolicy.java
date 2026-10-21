package com.example.autoauction.auction.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * AUCTION-01: the one canonical bid-step calculation (business decision BD-02):
 * {@code minimumBidStep = ROUND(startingPrice x 0.01, 3)}, mathematical (half-up) rounding.
 *
 * <p>Money amounts - prices and bids - keep their 2-decimal scale (the tyiyn); only the step carries 3
 * decimals. A bid is valid when it is {@code >= currentPrice + minimumBidStep} (exact comparison), so the
 * smallest valid bid is that threshold rounded UP to the tyiyn. The first bid follows the same rule with
 * {@code currentPrice == startingPrice} (BD-03).
 */
public final class BidStepPolicy {

    /** BD-02: 1% of the starting price. */
    public static final BigDecimal STEP_RATE = new BigDecimal("0.01");
    public static final int STEP_SCALE = 3;
    /** Bids and prices: som with tyiyn. */
    public static final int MONEY_SCALE = 2;
    /**
     * Technical capacity of the NUMERIC(10,2) price/bid columns - not a business maximum (BD-05: none is
     * defined yet), only the bound that keeps an amount from overflowing into a database error.
     */
    public static final BigDecimal MONEY_CAPACITY = new BigDecimal("99999999.99");

    private BidStepPolicy() {
    }

    public static BigDecimal minimumBidStep(BigDecimal startingPrice) {
        Objects.requireNonNull(startingPrice, "Стартовая цена не может быть null");
        if (startingPrice.signum() <= 0) {
            throw new IllegalArgumentException("Стартовая цена должна быть больше 0");
        }
        BigDecimal step = startingPrice.multiply(STEP_RATE).setScale(STEP_SCALE, RoundingMode.HALF_UP);
        // the existing rule "the bid step must be greater than 0" still holds: a price below 0.05 som
        // would round its 1% step down to zero
        if (step.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Стартовая цена слишком мала: шаг ставки (1% от стартовой цены) округляется до нуля");
        }
        return step;
    }

    /** The smallest money amount (2 decimals) that is not below {@code threshold}. */
    public static BigDecimal smallestValidBid(BigDecimal threshold) {
        return threshold.setScale(MONEY_SCALE, RoundingMode.CEILING);
    }

    /** True when the amount is positive money that fits the columns: at most 2 decimals, at most {@link #MONEY_CAPACITY}. */
    public static boolean isStorableMoney(BigDecimal amount) {
        return isMoney(amount) && amount.signum() > 0 && amount.compareTo(MONEY_CAPACITY) <= 0;
    }

    /** True when the amount is expressible in som and tyiyn (at most 2 decimals). */
    public static boolean isMoney(BigDecimal amount) {
        return amount != null && amount.stripTrailingZeros().scale() <= MONEY_SCALE;
    }
}
