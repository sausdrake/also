package com.example.autoauction.sale.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HandoverEvidence (X1)")
class HandoverEvidenceTest {

    private final Long auctionId = 1L;
    private final Long vehicleId = 5L;
    private final Long buyerId = 100L;
    private final Long confirmedByAdminId = 1L;
    private final OffsetDateTime now = OffsetDateTime.now();

    @Test
    @DisplayName("Валидное создание сохраняет все поля")
    void constructor_ValidInput_CreatesEvidence() {
        HandoverEvidence evidence = new HandoverEvidence(auctionId, vehicleId, buyerId, confirmedByAdminId, now, now);

        assertEquals(auctionId, evidence.getAuctionId());
        assertEquals(vehicleId, evidence.getVehicleId());
        assertEquals(buyerId, evidence.getBuyerId());
        assertEquals(confirmedByAdminId, evidence.getConfirmedByAdminId());
        assertEquals(now, evidence.getConfirmedAt());
        assertEquals(now, evidence.getCreatedAt());
        assertNull(evidence.getId());
    }

    @Test
    @DisplayName("auctionId == null отклоняется")
    void constructor_NullAuctionId_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () ->
                new HandoverEvidence(null, vehicleId, buyerId, confirmedByAdminId, now, now));
    }

    @Test
    @DisplayName("vehicleId == null отклоняется")
    void constructor_NullVehicleId_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () ->
                new HandoverEvidence(auctionId, null, buyerId, confirmedByAdminId, now, now));
    }

    @Test
    @DisplayName("buyerId == null отклоняется")
    void constructor_NullBuyerId_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () ->
                new HandoverEvidence(auctionId, vehicleId, null, confirmedByAdminId, now, now));
    }

    @Test
    @DisplayName("confirmedByAdminId == null отклоняется")
    void constructor_NullConfirmedByAdminId_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () ->
                new HandoverEvidence(auctionId, vehicleId, buyerId, null, now, now));
    }

    @Test
    @DisplayName("confirmedAt == null отклоняется")
    void constructor_NullConfirmedAt_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () ->
                new HandoverEvidence(auctionId, vehicleId, buyerId, confirmedByAdminId, null, now));
    }

    @Test
    @DisplayName("createdAt == null отклоняется")
    void constructor_NullCreatedAt_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () ->
                new HandoverEvidence(auctionId, vehicleId, buyerId, confirmedByAdminId, now, null));
    }

    @Test
    @DisplayName("Реконструкция (с id) восстанавливает состояние")
    void reconstructionConstructor_ValidInput_RestoresIdAndFields() {
        HandoverEvidence evidence = new HandoverEvidence(7L, auctionId, vehicleId, buyerId, confirmedByAdminId, now, now);
        assertEquals(7L, evidence.getId());
    }
}
