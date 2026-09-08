package com.example.autoauction.sale.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PaymentEvidence (X1)")
class PaymentEvidenceTest {

    private final Long auctionId = 1L;
    private final Long buyerId = 100L;
    private final Long receivedByAdminId = 1L;
    private final OffsetDateTime now = OffsetDateTime.now();

    @Test
    @DisplayName("Валидное создание сохраняет все поля")
    void constructor_ValidInput_CreatesEvidence() {
        PaymentEvidence evidence = new PaymentEvidence(auctionId, buyerId, new BigDecimal("1020000.00"),
                receivedByAdminId, now, now);

        assertEquals(auctionId, evidence.getAuctionId());
        assertEquals(buyerId, evidence.getBuyerId());
        assertEquals(0, new BigDecimal("1020000.00").compareTo(evidence.getAmount()));
        assertEquals(receivedByAdminId, evidence.getReceivedByAdminId());
        assertEquals(now, evidence.getReceivedAt());
        assertEquals(now, evidence.getCreatedAt());
        assertNull(evidence.getId(), "id остаётся null до персистентности");
    }

    @Test
    @DisplayName("amount округляется до scale=2 (HALF_UP)")
    void constructor_AmountScale_RoundsHalfUpToScale2() {
        // 333333 * 1.025 = 341666.325 -> HALF_UP -> 341666.33 (matches the locked monetary
        // rounding convention already established for Auction/PolicyVersion pricing)
        PaymentEvidence evidence = new PaymentEvidence(auctionId, buyerId, new BigDecimal("341666.325"),
                receivedByAdminId, now, now);
        assertEquals(0, new BigDecimal("341666.33").compareTo(evidence.getAmount()));
        assertEquals(2, evidence.getAmount().scale());
    }

    @Test
    @DisplayName("amount = 0 отклоняется")
    void constructor_ZeroAmount_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                new PaymentEvidence(auctionId, buyerId, BigDecimal.ZERO, receivedByAdminId, now, now));
    }

    @Test
    @DisplayName("amount < 0 отклоняется")
    void constructor_NegativeAmount_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                new PaymentEvidence(auctionId, buyerId, new BigDecimal("-1"), receivedByAdminId, now, now));
    }

    @Test
    @DisplayName("auctionId == null отклоняется")
    void constructor_NullAuctionId_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () ->
                new PaymentEvidence(null, buyerId, BigDecimal.TEN, receivedByAdminId, now, now));
    }

    @Test
    @DisplayName("buyerId == null отклоняется")
    void constructor_NullBuyerId_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () ->
                new PaymentEvidence(auctionId, null, BigDecimal.TEN, receivedByAdminId, now, now));
    }

    @Test
    @DisplayName("amount == null отклоняется")
    void constructor_NullAmount_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () ->
                new PaymentEvidence(auctionId, buyerId, null, receivedByAdminId, now, now));
    }

    @Test
    @DisplayName("receivedByAdminId == null отклоняется")
    void constructor_NullReceivedByAdminId_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () ->
                new PaymentEvidence(auctionId, buyerId, BigDecimal.TEN, null, now, now));
    }

    @Test
    @DisplayName("receivedAt == null отклоняется")
    void constructor_NullReceivedAt_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () ->
                new PaymentEvidence(auctionId, buyerId, BigDecimal.TEN, receivedByAdminId, null, now));
    }

    @Test
    @DisplayName("createdAt == null отклоняется")
    void constructor_NullCreatedAt_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () ->
                new PaymentEvidence(auctionId, buyerId, BigDecimal.TEN, receivedByAdminId, now, null));
    }

    @Test
    @DisplayName("Реконструкция (с id) восстанавливает состояние без повторной валидации границ, отличных от исходных")
    void reconstructionConstructor_ValidInput_RestoresIdAndFields() {
        PaymentEvidence evidence = new PaymentEvidence(42L, auctionId, buyerId, new BigDecimal("500.00"),
                receivedByAdminId, now, now);
        assertEquals(42L, evidence.getId());
        assertEquals(auctionId, evidence.getAuctionId());
    }
}
