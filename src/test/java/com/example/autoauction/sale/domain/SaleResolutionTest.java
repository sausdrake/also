package com.example.autoauction.sale.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SaleResolution (X1)")
class SaleResolutionTest {

    private final Long auctionId = 1L;
    private final Long resolvedBy = 1L;
    private final Long paymentEvidenceId = 10L;
    private final Long handoverEvidenceId = 20L;
    private final OffsetDateTime now = OffsetDateTime.now();

    private SaleResolution validSuccess() {
        return new SaleResolution(auctionId, resolvedBy, now, paymentEvidenceId, handoverEvidenceId, now);
    }

    @Test
    @DisplayName("Валидное создание -> SUCCESS, PENDING, оба evidence id сохранены")
    void constructor_ValidInput_CreatesSuccessPendingResolution() {
        SaleResolution resolution = validSuccess();

        assertEquals(auctionId, resolution.getAuctionId());
        assertEquals(SaleResolutionResult.SUCCESS, resolution.getResult());
        assertNull(resolution.getReason());
        assertEquals(resolvedBy, resolution.getResolvedBy());
        assertEquals(paymentEvidenceId, resolution.getPaymentEvidenceId());
        assertEquals(handoverEvidenceId, resolution.getHandoverEvidenceId());
        assertEquals(FinancialResolutionStatus.PENDING, resolution.getFinancialResolutionStatus());
        assertNull(resolution.getId());
    }

    @Test
    @DisplayName("SUCCESS без paymentEvidenceId отклоняется")
    void constructor_SuccessWithoutPaymentEvidence_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                new SaleResolution(auctionId, resolvedBy, now, null, handoverEvidenceId, now));
    }

    @Test
    @DisplayName("SUCCESS без handoverEvidenceId отклоняется")
    void constructor_SuccessWithoutHandoverEvidence_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                new SaleResolution(auctionId, resolvedBy, now, paymentEvidenceId, null, now));
    }

    @Test
    @DisplayName("SUCCESS без обоих evidence id отклоняется")
    void constructor_SuccessWithoutBothEvidence_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                new SaleResolution(auctionId, resolvedBy, now, null, null, now));
    }

    @Test
    @DisplayName("Реконструкция: SUCCESS без обоих evidence id отклоняется тоже (защита от повреждённых данных)")
    void reconstructionConstructor_SuccessWithoutEvidence_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                new SaleResolution(1L, auctionId, SaleResolutionResult.SUCCESS, null, null, resolvedBy, now,
                        null, null, FinancialResolutionStatus.PENDING, now));
    }

    @Test
    @DisplayName("auctionId == null отклоняется")
    void constructor_NullAuctionId_ThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () ->
                new SaleResolution(null, resolvedBy, now, paymentEvidenceId, handoverEvidenceId, now));
    }

    @Test
    @DisplayName("PENDING -> DONE разрешён")
    void markFinancialResolutionDone_FromPending_Succeeds() {
        SaleResolution resolution = validSuccess();
        resolution.markFinancialResolutionDone();
        assertEquals(FinancialResolutionStatus.DONE, resolution.getFinancialResolutionStatus());
    }

    @Test
    @DisplayName("PENDING -> FAILED разрешён")
    void markFinancialResolutionFailed_FromPending_Succeeds() {
        SaleResolution resolution = validSuccess();
        resolution.markFinancialResolutionFailed();
        assertEquals(FinancialResolutionStatus.FAILED, resolution.getFinancialResolutionStatus());
    }

    @Test
    @DisplayName("FAILED -> PENDING разрешён (явный сброс)")
    void resetFinancialResolutionToPending_FromFailed_Succeeds() {
        SaleResolution resolution = validSuccess();
        resolution.markFinancialResolutionFailed();
        resolution.resetFinancialResolutionToPending();
        assertEquals(FinancialResolutionStatus.PENDING, resolution.getFinancialResolutionStatus());
    }

    @Test
    @DisplayName("FAILED -> DONE запрещён")
    void markFinancialResolutionDone_FromFailed_ThrowsIllegalStateException() {
        SaleResolution resolution = validSuccess();
        resolution.markFinancialResolutionFailed();
        assertThrows(IllegalStateException.class, resolution::markFinancialResolutionDone);
        assertEquals(FinancialResolutionStatus.FAILED, resolution.getFinancialResolutionStatus(),
                "неудачная попытка не должна менять состояние");
    }

    @Test
    @DisplayName("DONE -> FAILED запрещён (DONE терминален)")
    void markFinancialResolutionFailed_FromDone_ThrowsIllegalStateException() {
        SaleResolution resolution = validSuccess();
        resolution.markFinancialResolutionDone();
        assertThrows(IllegalStateException.class, resolution::markFinancialResolutionFailed);
        assertEquals(FinancialResolutionStatus.DONE, resolution.getFinancialResolutionStatus());
    }

    @Test
    @DisplayName("DONE -> PENDING запрещён (DONE терминален, no silent rollback)")
    void resetFinancialResolutionToPending_FromDone_ThrowsIllegalStateException() {
        SaleResolution resolution = validSuccess();
        resolution.markFinancialResolutionDone();
        assertThrows(IllegalStateException.class, resolution::resetFinancialResolutionToPending);
        assertEquals(FinancialResolutionStatus.DONE, resolution.getFinancialResolutionStatus());
    }

    @Test
    @DisplayName("PENDING -> PENDING (resetFinancialResolutionToPending) запрещён - сброс возможен только из FAILED")
    void resetFinancialResolutionToPending_FromPending_ThrowsIllegalStateException() {
        SaleResolution resolution = validSuccess();
        assertThrows(IllegalStateException.class, resolution::resetFinancialResolutionToPending);
    }

    // ==================== X3-A: SALE_FAILED outcomes (B, BUYER_FAULT, PLATFORM_FAULT, C) ====================

    @Test
    @DisplayName("B + faultParty=null -> валидно, evidence id отсутствуют, PENDING")
    void constructor_OutcomeB_WithNullFaultParty_CreatesValidResolution() {
        SaleResolution resolution = new SaleResolution(auctionId, SaleResolutionResult.B, null,
                "Легитимная несостоявшаяся продажа", resolvedBy, now, now);

        assertEquals(SaleResolutionResult.B, resolution.getResult());
        assertNull(resolution.getFaultParty());
        assertEquals("Легитимная несостоявшаяся продажа", resolution.getReason());
        assertNull(resolution.getPaymentEvidenceId());
        assertNull(resolution.getHandoverEvidenceId());
        assertEquals(FinancialResolutionStatus.PENDING, resolution.getFinancialResolutionStatus());
    }

    @Test
    @DisplayName("BUYER_FAULT + faultParty=BUYER -> валидно")
    void constructor_OutcomeBuyerFault_WithMatchingFaultParty_CreatesValidResolution() {
        SaleResolution resolution = new SaleResolution(auctionId, SaleResolutionResult.BUYER_FAULT,
                FaultParty.BUYER, null, resolvedBy, now, now);

        assertEquals(SaleResolutionResult.BUYER_FAULT, resolution.getResult());
        assertEquals(FaultParty.BUYER, resolution.getFaultParty());
    }

    @Test
    @DisplayName("PLATFORM_FAULT + faultParty=PLATFORM -> валидно")
    void constructor_OutcomePlatformFault_WithMatchingFaultParty_CreatesValidResolution() {
        SaleResolution resolution = new SaleResolution(auctionId, SaleResolutionResult.PLATFORM_FAULT,
                FaultParty.PLATFORM, null, resolvedBy, now, now);

        assertEquals(SaleResolutionResult.PLATFORM_FAULT, resolution.getResult());
        assertEquals(FaultParty.PLATFORM, resolution.getFaultParty());
    }

    @Test
    @DisplayName("C + faultParty=SELLER -> валидно")
    void constructor_OutcomeC_WithMatchingFaultParty_CreatesValidResolution() {
        SaleResolution resolution = new SaleResolution(auctionId, SaleResolutionResult.C,
                FaultParty.SELLER, null, resolvedBy, now, now);

        assertEquals(SaleResolutionResult.C, resolution.getResult());
        assertEquals(FaultParty.SELLER, resolution.getFaultParty());
    }

    @Test
    @DisplayName("Неверные комбинации result/faultParty отклоняются")
    void constructor_InvalidResultFaultPartyCombinations_ThrowIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                new SaleResolution(auctionId, SaleResolutionResult.B, FaultParty.BUYER, null, resolvedBy, now, now));
        assertThrows(IllegalArgumentException.class, () ->
                new SaleResolution(auctionId, SaleResolutionResult.B, FaultParty.PLATFORM, null, resolvedBy, now, now));
        assertThrows(IllegalArgumentException.class, () ->
                new SaleResolution(auctionId, SaleResolutionResult.B, FaultParty.SELLER, null, resolvedBy, now, now));

        assertThrows(IllegalArgumentException.class, () ->
                new SaleResolution(auctionId, SaleResolutionResult.BUYER_FAULT, null, null, resolvedBy, now, now));
        assertThrows(IllegalArgumentException.class, () ->
                new SaleResolution(auctionId, SaleResolutionResult.BUYER_FAULT, FaultParty.PLATFORM, null, resolvedBy, now, now));
        assertThrows(IllegalArgumentException.class, () ->
                new SaleResolution(auctionId, SaleResolutionResult.BUYER_FAULT, FaultParty.SELLER, null, resolvedBy, now, now));

        assertThrows(IllegalArgumentException.class, () ->
                new SaleResolution(auctionId, SaleResolutionResult.PLATFORM_FAULT, null, null, resolvedBy, now, now));
        assertThrows(IllegalArgumentException.class, () ->
                new SaleResolution(auctionId, SaleResolutionResult.PLATFORM_FAULT, FaultParty.BUYER, null, resolvedBy, now, now));
        assertThrows(IllegalArgumentException.class, () ->
                new SaleResolution(auctionId, SaleResolutionResult.PLATFORM_FAULT, FaultParty.SELLER, null, resolvedBy, now, now));

        assertThrows(IllegalArgumentException.class, () ->
                new SaleResolution(auctionId, SaleResolutionResult.C, null, null, resolvedBy, now, now));
        assertThrows(IllegalArgumentException.class, () ->
                new SaleResolution(auctionId, SaleResolutionResult.C, FaultParty.BUYER, null, resolvedBy, now, now));
        assertThrows(IllegalArgumentException.class, () ->
                new SaleResolution(auctionId, SaleResolutionResult.C, FaultParty.PLATFORM, null, resolvedBy, now, now));
    }

    @Test
    @DisplayName("SUCCESS + любой faultParty != null отклоняется (реконструкция)")
    void reconstructionConstructor_SuccessWithNonNullFaultParty_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                new SaleResolution(1L, auctionId, SaleResolutionResult.SUCCESS, null, FaultParty.BUYER, resolvedBy,
                        now, paymentEvidenceId, handoverEvidenceId, FinancialResolutionStatus.PENDING, now));
    }

    @Test
    @DisplayName("failureReason сохраняется и доступен через getReason()")
    void constructor_FailureReason_IsPersistedAsReason() {
        SaleResolution resolution = new SaleResolution(auctionId, SaleResolutionResult.C, FaultParty.SELLER,
                "Продавец подменил комплектацию автомобиля", resolvedBy, now, now);

        assertEquals("Продавец подменил комплектацию автомобиля", resolution.getReason());
    }

    @Test
    @DisplayName("failureReason == null допустим (не обязателен)")
    void constructor_NullFailureReason_IsAllowed() {
        SaleResolution resolution = new SaleResolution(auctionId, SaleResolutionResult.B, null,
                null, resolvedBy, now, now);

        assertNull(resolution.getReason());
    }

    @Test
    @DisplayName("Существующее создание SUCCESS не изменилось - faultParty всегда null")
    void constructor_ExistingSuccessCreation_StillHasNullFaultParty() {
        SaleResolution resolution = validSuccess();
        assertNull(resolution.getFaultParty());
    }
}
