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
                new SaleResolution(1L, auctionId, SaleResolutionResult.SUCCESS, null, resolvedBy, now,
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
}
