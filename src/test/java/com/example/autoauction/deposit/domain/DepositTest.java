// deposit/domain/DepositTest.java
package com.example.autoauction.deposit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PHASE 3B: pure domain-level tests for {@link Deposit#chargeFromFrozen}, the new
 * frozen-funds charge operation. No mocks, no Spring - this method's own boundary/
 * precondition logic is exhaustively testable in isolation, matching how the codebase's
 * other Deposit domain methods (freezeForAuction/unfreezeForAuction/withdraw) are already
 * indirectly exercised, but exercised here directly since chargeFromFrozen is new.
 */
@DisplayName("Deposit.chargeFromFrozen()")
class DepositTest {

    private static final Long USER_ID = 1L;

    private Deposit depositWith(BigDecimal balance, BigDecimal blocked) {
        Deposit deposit = new Deposit(USER_ID, balance);
        if (blocked.compareTo(BigDecimal.ZERO) > 0) {
            deposit.freezeForAuction(blocked);
        }
        return deposit;
    }

    @Test
    @DisplayName("Частичный charge: blockedAmount и balance уменьшаются на разные суммы, available отражает refund")
    void chargeFromFrozen_PartialCharge_CorrectDeltas() {
        Deposit deposit = depositWith(new BigDecimal("20000"), new BigDecimal("10000"));

        deposit.chargeFromFrozen(new BigDecimal("10000"), new BigDecimal("4000"));

        assertThat(deposit.getBalance()).isEqualByComparingTo("16000");
        assertThat(deposit.getBlockedAmount()).isEqualByComparingTo("0");
        assertThat(deposit.getAvailableBalance()).isEqualByComparingTo("16000");
    }

    @Test
    @DisplayName("Полный charge (chargeAmount == totalFrozen): refund = 0")
    void chargeFromFrozen_FullCharge_ZeroRefund() {
        Deposit deposit = depositWith(new BigDecimal("20000"), new BigDecimal("10000"));

        deposit.chargeFromFrozen(new BigDecimal("10000"), new BigDecimal("10000"));

        assertThat(deposit.getBalance()).isEqualByComparingTo("10000");
        assertThat(deposit.getBlockedAmount()).isEqualByComparingTo("0");
        assertThat(deposit.getAvailableBalance()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("Нулевой charge (chargeAmount == 0): полный возврат, balance не меняется")
    void chargeFromFrozen_ZeroCharge_FullRefund() {
        Deposit deposit = depositWith(new BigDecimal("20000"), new BigDecimal("10000"));

        deposit.chargeFromFrozen(new BigDecimal("10000"), BigDecimal.ZERO);

        assertThat(deposit.getBalance()).isEqualByComparingTo("20000");
        assertThat(deposit.getBlockedAmount()).isEqualByComparingTo("0");
        assertThat(deposit.getAvailableBalance()).isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("chargeAmount > totalFrozen — IllegalArgumentException, без мутации")
    void chargeFromFrozen_ChargeExceedsTotalFrozen_Throws() {
        Deposit deposit = depositWith(new BigDecimal("20000"), new BigDecimal("10000"));

        assertThatThrownBy(() -> deposit.chargeFromFrozen(new BigDecimal("10000"), new BigDecimal("10001")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(deposit.getBalance()).isEqualByComparingTo("20000");
        assertThat(deposit.getBlockedAmount()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("Отрицательный chargeAmount — IllegalArgumentException")
    void chargeFromFrozen_NegativeCharge_Throws() {
        Deposit deposit = depositWith(new BigDecimal("20000"), new BigDecimal("10000"));

        assertThatThrownBy(() -> deposit.chargeFromFrozen(new BigDecimal("10000"), new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Нулевой или отрицательный totalFrozen — IllegalArgumentException")
    void chargeFromFrozen_NonPositiveTotalFrozen_Throws() {
        Deposit deposit = depositWith(new BigDecimal("20000"), new BigDecimal("10000"));

        assertThatThrownBy(() -> deposit.chargeFromFrozen(BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deposit.chargeFromFrozen(new BigDecimal("-1"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("blockedAmount меньше totalFrozen — IllegalStateException, без мутации")
    void chargeFromFrozen_InsufficientBlockedAmount_Throws() {
        Deposit deposit = depositWith(new BigDecimal("20000"), new BigDecimal("5000"));

        assertThatThrownBy(() -> deposit.chargeFromFrozen(new BigDecimal("10000"), new BigDecimal("4000")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(deposit.getBalance()).isEqualByComparingTo("20000");
        assertThat(deposit.getBlockedAmount()).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("availableBalance инвариант: после мутации всегда равен balance - blockedAmount")
    void chargeFromFrozen_AvailableBalanceInvariant() {
        Deposit deposit = depositWith(new BigDecimal("35000"), new BigDecimal("12000"));

        deposit.chargeFromFrozen(new BigDecimal("12000"), new BigDecimal("5000"));

        assertThat(deposit.getAvailableBalance())
                .isEqualByComparingTo(deposit.getBalance().subtract(deposit.getBlockedAmount()));
    }
}
