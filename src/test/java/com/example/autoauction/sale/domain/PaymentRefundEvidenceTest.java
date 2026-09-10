package com.example.autoauction.sale.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Slice 1: domain-level validation of {@link PaymentRefundEvidence} - mirrors
 * {@code PaymentEvidenceTest}. The refunded-amount == paid-amount equality is a
 * PaymentRefundService construction guarantee (tested there), not enforced here; this class
 * only covers the type-level {@code > 0} floor and null-checks shared with PaymentEvidence.
 */
@DisplayName("PaymentRefundEvidence — Slice 1 domain unit tests")
class PaymentRefundEvidenceTest {

    private final OffsetDateTime now = OffsetDateTime.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("Валидный конструктор: поля сохраняются, сумма приводится к scale 2")
    void constructor_Valid_KeepsFieldsAndRescales() {
        PaymentRefundEvidence e = new PaymentRefundEvidence(
                10L, 20L, 30L, new BigDecimal("1020000"), 1L, now, now);

        assertThat(e.getPaymentEvidenceId()).isEqualTo(10L);
        assertThat(e.getAuctionId()).isEqualTo(20L);
        assertThat(e.getBuyerId()).isEqualTo(30L);
        assertThat(e.getRefundedAmount()).isEqualByComparingTo("1020000.00");
        assertThat(e.getRefundedAmount().scale()).isEqualTo(2);
        assertThat(e.getRefundedByAdminId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Нулевая или отрицательная сумма отклоняется")
    void constructor_NonPositiveAmount_Rejected() {
        assertThatThrownBy(() -> new PaymentRefundEvidence(10L, 20L, 30L, BigDecimal.ZERO, 1L, now, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentRefundEvidence(10L, 20L, 30L, new BigDecimal("-1"), 1L, now, now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null в обязательных полях отклоняется")
    void constructor_NullRequiredFields_Rejected() {
        assertThatThrownBy(() -> new PaymentRefundEvidence(null, 20L, 30L, BigDecimal.TEN, 1L, now, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentRefundEvidence(10L, null, 30L, BigDecimal.TEN, 1L, now, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentRefundEvidence(10L, 20L, null, BigDecimal.TEN, 1L, now, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentRefundEvidence(10L, 20L, 30L, null, 1L, now, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentRefundEvidence(10L, 20L, 30L, BigDecimal.TEN, null, now, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentRefundEvidence(10L, 20L, 30L, BigDecimal.TEN, 1L, null, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentRefundEvidence(10L, 20L, 30L, BigDecimal.TEN, 1L, now, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Reconstruction-конструктор восстанавливает id и повторно валидирует")
    void reconstructionConstructor_RestoresIdAndRevalidates() {
        assertDoesNotThrow(() -> new PaymentRefundEvidence(
                99L, 10L, 20L, 30L, new BigDecimal("500.00"), 1L, now, now));
        assertThatThrownBy(() -> new PaymentRefundEvidence(
                99L, 10L, 20L, 30L, new BigDecimal("0.00"), 1L, now, now))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
