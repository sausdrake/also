package com.example.autoauction.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Step 2C: SettlementCorrection — type/value-combination validation")
class SettlementCorrectionTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-10T10:00:00Z");

    @Test
    void amountCorrection_validCombination_accepted() {
        SettlementCorrection c = new SettlementCorrection(1L, SettlementCorrectionType.AMOUNT_CORRECTION,
                new BigDecimal("960000.00"), null, "typo in original amount", 99L, NOW, "key-1");
        assertThat(c.getCorrectionType()).isEqualTo(SettlementCorrectionType.AMOUNT_CORRECTION);
        assertThat(c.getCorrectedAmount()).isEqualByComparingTo("960000.00");
        assertThat(c.getCorrectedSellerId()).isNull();
    }

    @Test
    void amountCorrection_missingAmount_rejected() {
        assertThatThrownBy(() -> new SettlementCorrection(1L, SettlementCorrectionType.AMOUNT_CORRECTION,
                null, null, "reason", 99L, NOW, "key-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void amountCorrection_nonPositiveAmount_rejected() {
        assertThatThrownBy(() -> new SettlementCorrection(1L, SettlementCorrectionType.AMOUNT_CORRECTION,
                BigDecimal.ZERO, null, "reason", 99L, NOW, "key-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void amountCorrection_withSellerIdAlsoPopulated_rejected() {
        assertThatThrownBy(() -> new SettlementCorrection(1L, SettlementCorrectionType.AMOUNT_CORRECTION,
                new BigDecimal("960000"), 2L, "reason", 99L, NOW, "key-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Phase 6 corrective pass (Fix 2): correctedAmount must be normalized to the same canonical
     * scale-2/HALF_UP convention createPending already applies to its own computed amounts.
     */
    @Test
    void amountCorrection_correctedAmount_normalizedToScale2HalfUp() {
        SettlementCorrection c = new SettlementCorrection(1L, SettlementCorrectionType.AMOUNT_CORRECTION,
                new BigDecimal("970000.005"), null, "recount", 99L, NOW, "key-1");
        assertThat(c.getCorrectedAmount()).isEqualByComparingTo("970000.01");
    }

    @Test
    void amountCorrection_correctedAmount_halfUpBoundary_roundsDownWhenBelowHalf() {
        SettlementCorrection c = new SettlementCorrection(1L, SettlementCorrectionType.AMOUNT_CORRECTION,
                new BigDecimal("970000.004"), null, "recount", 99L, NOW, "key-1");
        assertThat(c.getCorrectedAmount()).isEqualByComparingTo("970000.00");
    }

    @Test
    void sellerCorrection_validCombination_accepted() {
        SettlementCorrection c = new SettlementCorrection(1L, SettlementCorrectionType.SELLER_CORRECTION,
                null, 5L, "cash handed to the wrong person", 99L, NOW, "key-2");
        assertThat(c.getCorrectedSellerId()).isEqualTo(5L);
        assertThat(c.getCorrectedAmount()).isNull();
    }

    @Test
    void sellerCorrection_missingSellerId_rejected() {
        assertThatThrownBy(() -> new SettlementCorrection(1L, SettlementCorrectionType.SELLER_CORRECTION,
                null, null, "reason", 99L, NOW, "key-2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sellerCorrection_withAmountAlsoPopulated_rejected() {
        assertThatThrownBy(() -> new SettlementCorrection(1L, SettlementCorrectionType.SELLER_CORRECTION,
                new BigDecimal("1"), 5L, "reason", 99L, NOW, "key-2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void payoutInvalidated_validCombination_acceptsNeitherValue() {
        SettlementCorrection c = new SettlementCorrection(1L, SettlementCorrectionType.PAYOUT_INVALIDATED,
                null, null, "original attestation was entirely erroneous", 99L, NOW, "key-3");
        assertThat(c.getCorrectedAmount()).isNull();
        assertThat(c.getCorrectedSellerId()).isNull();
    }

    @Test
    void payoutInvalidated_withEitherValuePopulated_rejected() {
        assertThatThrownBy(() -> new SettlementCorrection(1L, SettlementCorrectionType.PAYOUT_INVALIDATED,
                new BigDecimal("1"), null, "reason", 99L, NOW, "key-3"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementCorrection(1L, SettlementCorrectionType.PAYOUT_INVALIDATED,
                null, 5L, "reason", 99L, NOW, "key-3"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankOrNullReason_rejected() {
        assertThatThrownBy(() -> new SettlementCorrection(1L, SettlementCorrectionType.PAYOUT_INVALIDATED,
                null, null, null, 99L, NOW, "key-3"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementCorrection(1L, SettlementCorrectionType.PAYOUT_INVALIDATED,
                null, null, "   ", 99L, NOW, "key-3"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankOrNullIdempotencyKey_rejected() {
        assertThatThrownBy(() -> new SettlementCorrection(1L, SettlementCorrectionType.PAYOUT_INVALIDATED,
                null, null, "reason", 99L, NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementCorrection(1L, SettlementCorrectionType.PAYOUT_INVALIDATED,
                null, null, "reason", 99L, NOW, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullSettlementIdOrAdminOrTimestamp_rejected() {
        assertThatThrownBy(() -> new SettlementCorrection(null, SettlementCorrectionType.PAYOUT_INVALIDATED,
                null, null, "reason", 99L, NOW, "key-3"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SettlementCorrection(1L, SettlementCorrectionType.PAYOUT_INVALIDATED,
                null, null, "reason", null, NOW, "key-3"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SettlementCorrection(1L, SettlementCorrectionType.PAYOUT_INVALIDATED,
                null, null, "reason", 99L, null, "key-3"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void reconstruction_revalidatesCombination_rejectsCorruptedRow() {
        assertThatThrownBy(() -> new SettlementCorrection(10L, 1L, SettlementCorrectionType.AMOUNT_CORRECTION,
                null, null, "reason", 99L, NOW, "key-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reconstruction_restoresIdExactly() {
        SettlementCorrection c = new SettlementCorrection(10L, 1L, SettlementCorrectionType.SELLER_CORRECTION,
                null, 5L, "reason", 99L, NOW, "key-2");
        assertThat(c.getId()).isEqualTo(10L);
        assertThat(c.getSettlementId()).isEqualTo(1L);
    }
}
