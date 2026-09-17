package com.example.autoauction.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Slice 2: PaymentAmountPolicy — locked KGS limits, tolerance 0, no monthly limit")
class PaymentAmountPolicyTest {

    @Test
    void lockedConstants() {
        assertThat(PaymentAmountPolicy.MIN_TOP_UP).isEqualByComparingTo("100");
        assertThat(PaymentAmountPolicy.MAX_TOP_UP).isEqualByComparingTo("200000");
        assertThat(PaymentAmountPolicy.DAILY_LIMIT).isEqualByComparingTo("1000000");
        assertThat(PaymentAmountPolicy.OVERPAYMENT_TOLERANCE).isEqualByComparingTo("0");
    }

    @Test
    void amount_belowMin_rejected() {
        assertThatThrownBy(() -> PaymentAmountPolicy.validateAmount(new BigDecimal("99")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void amount_atMin_accepted() {
        assertThatCode(() -> PaymentAmountPolicy.validateAmount(new BigDecimal("100"))).doesNotThrowAnyException();
    }

    @Test
    void amount_atMax_accepted() {
        assertThatCode(() -> PaymentAmountPolicy.validateAmount(new BigDecimal("200000"))).doesNotThrowAnyException();
    }

    @Test
    void amount_aboveMax_rejected() {
        assertThatThrownBy(() -> PaymentAmountPolicy.validateAmount(new BigDecimal("200001")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void amount_null_rejected() {
        assertThatThrownBy(() -> PaymentAmountPolicy.validateAmount(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void dailyLimit_exactlyAtLimit_accepted() {
        assertThatCode(() -> PaymentAmountPolicy.validateDailyLimit(new BigDecimal("999900"), new BigDecimal("100")))
                .doesNotThrowAnyException();
    }

    @Test
    void dailyLimit_oneOverLimit_rejected() {
        assertThatThrownBy(() -> PaymentAmountPolicy.validateDailyLimit(new BigDecimal("999901"), new BigDecimal("100")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dailyLimit_freshUser_accepted() {
        assertThatCode(() -> PaymentAmountPolicy.validateDailyLimit(BigDecimal.ZERO, new BigDecimal("200000")))
                .doesNotThrowAnyException();
    }

    @Test
    void amountsMatch_toleranceIsZero() {
        assertThat(PaymentAmountPolicy.amountsMatch(new BigDecimal("10000.00"), new BigDecimal("10000.00"))).isTrue();
        assertThat(PaymentAmountPolicy.amountsMatch(new BigDecimal("10000"), new BigDecimal("10000.00"))).isTrue();
        assertThat(PaymentAmountPolicy.amountsMatch(new BigDecimal("10000"), new BigDecimal("10001"))).isFalse();
        assertThat(PaymentAmountPolicy.amountsMatch(new BigDecimal("10000"), new BigDecimal("9999"))).isFalse();
        assertThat(PaymentAmountPolicy.amountsMatch(new BigDecimal("10000"), new BigDecimal("10000.01"))).isFalse();
        assertThat(PaymentAmountPolicy.amountsMatch(null, new BigDecimal("10000"))).isFalse();
    }
}
