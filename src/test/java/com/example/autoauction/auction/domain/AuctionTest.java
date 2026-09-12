package com.example.autoauction.auction.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

class AuctionTest {

    private Auction auction;
    private final Long vehicleId = 1L;
    private final BigDecimal startingPrice = new BigDecimal("1000000");
    private final BigDecimal reservePrice = new BigDecimal("1200000");
    private final BigDecimal buyNowPrice = new BigDecimal("1500000");
    private final BigDecimal minBidStep = new BigDecimal("10000");
    private final OffsetDateTime startTime = OffsetDateTime.now().plusDays(1);
    private final OffsetDateTime endTime = startTime.plusDays(7);
    private final Long createdBy = 1L;

    @BeforeEach
    void setUp() {
        auction = new Auction(
                vehicleId,
                startingPrice,
                reservePrice,
                buyNowPrice,
                minBidStep,
                startTime,
                endTime,
                createdBy
        );
    }

    @Test
    void constructor_ShouldCreateAuctionWithCorrectInitialState() {
        assertNotNull(auction);
        assertEquals(vehicleId, auction.getVehicleId());
        assertEquals(startingPrice, auction.getStartingPrice());
        assertEquals(startingPrice, auction.getCurrentPrice());
        assertEquals(reservePrice, auction.getReservePrice());
        assertEquals(buyNowPrice, auction.getBuyNowPrice());
        assertEquals(minBidStep, auction.getMinBidStep());
        assertEquals(startTime, auction.getStartTime());
        assertEquals(endTime, auction.getEndTime());
        assertEquals(createdBy, auction.getCreatedBy());
        assertEquals(AuctionStatus.CREATED, auction.getStatus());
        assertEquals(0, auction.getTotalBids());
        assertNotNull(auction.getCreatedAt());
        assertNotNull(auction.getUpdatedAt());
    }

    @Test
    void start_ShouldChangeStatusToActive() {
        // when
        auction.start();

        // then
        assertEquals(AuctionStatus.ACTIVE, auction.getStatus());
        assertNotNull(auction.getUpdatedAt());
    }

    @Test
    void start_WhenNotCreated_ShouldThrowException() {
        // given
        auction.start();
        assertEquals(AuctionStatus.ACTIVE, auction.getStatus());

        // when & then
        assertThrows(IllegalStateException.class, () -> auction.start());
    }

    @Test
    void markSold_WhenWaitingForPayment_ShouldChangeStatusToSold() {
        // given
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);

        // when
        auction.markSold();

        // then
        assertEquals(AuctionStatus.SOLD, auction.getStatus());
        assertNotNull(auction.getUpdatedAt());
    }

    @Test
    void markSold_WhenCreated_ShouldThrowException() {
        assertThrows(IllegalStateException.class, () -> auction.markSold());
    }

    @Test
    void markSold_WhenActive_ShouldThrowException() {
        auction.setStatus(AuctionStatus.ACTIVE);
        assertThrows(IllegalStateException.class, () -> auction.markSold());
    }

    @Test
    void markSold_WhenAlreadySold_ShouldThrowException() {
        auction.setStatus(AuctionStatus.SOLD);
        assertThrows(IllegalStateException.class, () -> auction.markSold());
    }

    @Test
    void markSold_WhenSaleFailed_ShouldThrowException() {
        auction.setStatus(AuctionStatus.SALE_FAILED);
        assertThrows(IllegalStateException.class, () -> auction.markSold());
    }

    @Test
    void markSold_WhenExpired_ShouldThrowException() {
        auction.setStatus(AuctionStatus.EXPIRED);
        assertThrows(IllegalStateException.class, () -> auction.markSold());
    }

    @Test
    void markSold_WhenCancelled_ShouldThrowException() {
        auction.setStatus(AuctionStatus.CANCELLED);
        assertThrows(IllegalStateException.class, () -> auction.markSold());
    }

    @Test
    @SuppressWarnings("deprecation")
    void markSold_WhenLegacyCompleted_ShouldThrowException() {
        auction.setStatus(AuctionStatus.COMPLETED);
        assertThrows(IllegalStateException.class, () -> auction.markSold());
    }

    @Test
    void markSaleFailed_WhenWaitingForPayment_ShouldChangeStatusToSaleFailed() {
        // given
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);

        // when
        auction.markSaleFailed();

        // then
        assertEquals(AuctionStatus.SALE_FAILED, auction.getStatus());
        assertNotNull(auction.getUpdatedAt());
    }

    @Test
    void markSaleFailed_WhenCreated_ShouldThrowException() {
        assertThrows(IllegalStateException.class, () -> auction.markSaleFailed());
    }

    @Test
    void markSaleFailed_WhenActive_ShouldThrowException() {
        auction.setStatus(AuctionStatus.ACTIVE);
        assertThrows(IllegalStateException.class, () -> auction.markSaleFailed());
    }

    @Test
    void markSaleFailed_WhenSold_ShouldThrowException() {
        auction.setStatus(AuctionStatus.SOLD);
        assertThrows(IllegalStateException.class, () -> auction.markSaleFailed());
    }

    @Test
    void markSaleFailed_WhenAlreadySaleFailed_ShouldThrowException() {
        auction.setStatus(AuctionStatus.SALE_FAILED);
        assertThrows(IllegalStateException.class, () -> auction.markSaleFailed());
    }

    @Test
    void markSaleFailed_WhenExpired_ShouldThrowException() {
        auction.setStatus(AuctionStatus.EXPIRED);
        assertThrows(IllegalStateException.class, () -> auction.markSaleFailed());
    }

    @Test
    void markSaleFailed_WhenCancelled_ShouldThrowException() {
        auction.setStatus(AuctionStatus.CANCELLED);
        assertThrows(IllegalStateException.class, () -> auction.markSaleFailed());
    }

    @Test
    @SuppressWarnings("deprecation")
    void markSaleFailed_WhenLegacyCompleted_ShouldThrowException() {
        auction.setStatus(AuctionStatus.COMPLETED);
        assertThrows(IllegalStateException.class, () -> auction.markSaleFailed());
    }

    @Test
    void cancel_WhenCreated_ShouldChangeStatusToCancelled() {
        // when
        auction.cancel();

        // then
        assertEquals(AuctionStatus.CANCELLED, auction.getStatus());
    }

    @Test
    void cancel_WhenActive_ShouldChangeStatusToCancelled() {
        // given
        auction.start();

        // when
        auction.cancel();

        // then
        assertEquals(AuctionStatus.CANCELLED, auction.getStatus());
    }

    @Test
    void cancel_WhenAlreadyCancelled_ShouldThrowException() {
        // given
        auction.cancel();

        // when & then
        assertThrows(IllegalStateException.class, () -> auction.cancel());
    }

    @Test
    void cancel_WhenWaitingForPayment_ShouldThrowException() {
        // given
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);

        // when & then
        assertThrows(IllegalStateException.class, () -> auction.cancel());
    }

    @Test
    void isCancellable_WhenCreated_ShouldBeTrue() {
        assertTrue(auction.isCancellable());
    }

    @Test
    void isCancellable_WhenActive_ShouldBeTrue() {
        auction.start();
        assertTrue(auction.isCancellable());
    }

    @Test
    void isCancellable_WhenWaitingForPayment_ShouldBeFalse() {
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        assertFalse(auction.isCancellable());
    }

    @Test
    void isFinished_WhenWaitingForPayment_ShouldBeFalse() {
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        assertFalse(auction.isFinished());
    }

    @Test
    void isFinished_WhenSold_ShouldBeTrue() {
        auction.setStatus(AuctionStatus.SOLD);
        assertTrue(auction.isFinished());
    }

    @Test
    void isFinished_WhenSaleFailed_ShouldBeTrue() {
        auction.setStatus(AuctionStatus.SALE_FAILED);
        assertTrue(auction.isFinished());
    }

    @Test
    void isFinished_WhenLegacyCompleted_ShouldRemainTrue() {
        auction.setStatus(AuctionStatus.COMPLETED);
        assertTrue(auction.isFinished());
    }

    @Test
    void addBid_ShouldIncreaseCurrentPriceAndTotalBids() {
        // given
        auction.start();
        BigDecimal bidAmount = startingPrice.add(minBidStep);
        Long bidderId = 2L;

        // when
        auction.addBid(bidAmount, bidderId);

        // then
        assertEquals(bidAmount, auction.getCurrentPrice());
        assertEquals(1, auction.getTotalBids());
        assertNotNull(auction.getUpdatedAt());
    }

    @Test
    void addBid_WhenAuctionNotActive_ShouldThrowException() {
        // given
        BigDecimal bidAmount = startingPrice.add(minBidStep);
        Long bidderId = 2L;

        // when & then
        assertThrows(IllegalStateException.class, () -> auction.addBid(bidAmount, bidderId));
    }

    @Test
    void addBid_WhenBidTooLow_ShouldThrowException() {
        // given
        auction.start();
        BigDecimal bidAmount = startingPrice.add(minBidStep.subtract(new BigDecimal("1")));
        Long bidderId = 2L;

        // when & then
        assertThrows(IllegalArgumentException.class, () -> auction.addBid(bidAmount, bidderId));
    }

    @Test
    void addBid_WhenBidderIsCreator_ShouldThrowException() {
        // given
        auction.start();
        BigDecimal bidAmount = startingPrice.add(minBidStep);
        Long bidderId = createdBy;

        // when & then
        assertThrows(IllegalArgumentException.class, () -> auction.addBid(bidAmount, bidderId));
    }

    @Test
    void updateVehicleInfo_ShouldSetVehicleInfo() {
        // given
        String vehicleInfo = "BMW M5 2023";

        // when
        auction.updateVehicleInfo(vehicleInfo);

        // then
        assertEquals(vehicleInfo, auction.getVehicleInfo());
        assertNotNull(auction.getUpdatedAt());
    }

    @Test
    void setVehicleInfo_ShouldSetVehicleInfo() {
        // given
        String vehicleInfo = "BMW M5 2023";

        // when
        auction.setVehicleInfo(vehicleInfo);

        // then
        assertEquals(vehicleInfo, auction.getVehicleInfo());
        assertNotNull(auction.getUpdatedAt());
    }

    // ==================== Slice 1b-2: policyVersionId + buyer-facing price resolution ====================

    @Test
    void policyVersionId_DefaultsToNull() {
        assertNull(auction.getPolicyVersionId());
    }

    @Test
    void setPolicyVersionId_ShouldSetValueAndTouchUpdatedAt() {
        // given
        var before = auction.getUpdatedAt();

        // when
        auction.setPolicyVersionId(42L);

        // then
        assertEquals(42L, auction.getPolicyVersionId());
        assertNotNull(auction.getUpdatedAt());
        assertTrue(!auction.getUpdatedAt().isBefore(before));
    }

    // ==================== X3-C: sellerDiagnosticCommitmentId ====================

    @Test
    void sellerDiagnosticCommitmentId_DefaultsToNull() {
        assertNull(auction.getSellerDiagnosticCommitmentId());
    }

    @Test
    @DisplayName("X3-C CORRECTIVE: new Auction получает commitment ID ровно один раз")
    void setSellerDiagnosticCommitmentId_FirstCall_AssignsValueAndTouchesUpdatedAt() {
        // given
        var before = auction.getUpdatedAt();

        // when
        auction.setSellerDiagnosticCommitmentId(99L);

        // then
        assertEquals(99L, auction.getSellerDiagnosticCommitmentId());
        assertNotNull(auction.getUpdatedAt());
        assertTrue(!auction.getUpdatedAt().isBefore(before));
    }

    @Test
    @DisplayName("X3-C CORRECTIVE: историческая реконструкция с NULL остаётся допустимой (первый вызов, значение null)")
    void setSellerDiagnosticCommitmentId_FirstCallWithNull_Allowed() {
        // Mirrors AuctionMapper.toDomain() reconstructing a legacy, pre-X3-C row: the field is
        // still unassigned on this fresh instance, so a first call restoring null must succeed,
        // not be mistaken for "already assigned".
        auction.setSellerDiagnosticCommitmentId(null);

        assertNull(auction.getSellerDiagnosticCommitmentId());
    }

    @Test
    @DisplayName("X3-C CORRECTIVE: invariant I5 - commitment ID не может быть reassigned через публичный Auction API")
    void setSellerDiagnosticCommitmentId_SecondCall_ThrowsRegardlessOfValue() {
        auction.setSellerDiagnosticCommitmentId(99L);

        // reassignment to a different value
        IllegalStateException exDifferent = assertThrows(IllegalStateException.class,
                () -> auction.setSellerDiagnosticCommitmentId(100L));
        assertTrue(exDifferent.getMessage().contains("99"));
        assertEquals(99L, auction.getSellerDiagnosticCommitmentId(), "value must remain unchanged after a rejected reassignment attempt");

        // "clearing" it is also a reassignment attempt, not a special-cased allowed case
        assertThrows(IllegalStateException.class,
                () -> auction.setSellerDiagnosticCommitmentId(null));
        assertEquals(99L, auction.getSellerDiagnosticCommitmentId());

        // re-assigning the SAME value is still rejected - "once set" means exactly one call ever
        assertThrows(IllegalStateException.class,
                () -> auction.setSellerDiagnosticCommitmentId(99L));
    }

    @Test
    @DisplayName("X3-C CORRECTIVE: existing policyVersionId behavior не регрессировал (остаётся plain, independent setter)")
    void setSellerDiagnosticCommitmentId_DoesNotAffectPolicyVersionId() {
        auction.setPolicyVersionId(7L);
        auction.setSellerDiagnosticCommitmentId(99L);

        assertEquals(7L, auction.getPolicyVersionId());
        assertEquals(99L, auction.getSellerDiagnosticCommitmentId());

        // policyVersionId remains a plain, unguarded setter - explicitly out of scope for this
        // corrective fix, and must still accept a second call without throwing.
        auction.setPolicyVersionId(8L);
        assertEquals(8L, auction.getPolicyVersionId());
    }

    @Test
    void resolveBuyerFacingReservePrice_LegacyAuction_ReturnsRawValueUnchanged() {
        // given: policyVersionId is null (legacy auction, grandfathered)
        assertNull(auction.getPolicyVersionId());

        // when
        BigDecimal result = auction.resolveBuyerFacingReservePrice(new BigDecimal("0.05"));

        // then: commissionRate is completely ignored for a legacy auction
        assertEquals(0, reservePrice.compareTo(result));
    }

    @Test
    void resolveBuyerFacingBuyNowPrice_LegacyAuction_ReturnsRawValueUnchanged() {
        assertNull(auction.getPolicyVersionId());

        BigDecimal result = auction.resolveBuyerFacingBuyNowPrice(new BigDecimal("0.05"));

        assertEquals(0, buyNowPrice.compareTo(result));
    }

    @Test
    void resolveBuyerFacingReservePrice_NullReservePrice_ReturnsNullRegardlessOfPolicyBinding() {
        // given
        auction.setPolicyVersionId(1L);
        Auction noReserve = new Auction(vehicleId, startingPrice, null, buyNowPrice, minBidStep,
                startTime, endTime, createdBy);
        noReserve.setPolicyVersionId(1L);

        // when & then
        assertNull(noReserve.resolveBuyerFacingReservePrice(new BigDecimal("0.05")));
    }

    @Test
    void resolveBuyerFacingBuyNowPrice_NullBuyNowPrice_ReturnsNullRegardlessOfPolicyBinding() {
        Auction noBuyNow = new Auction(vehicleId, startingPrice, reservePrice, null, minBidStep,
                startTime, endTime, createdBy);
        noBuyNow.setPolicyVersionId(1L);

        assertNull(noBuyNow.resolveBuyerFacingBuyNowPrice(new BigDecimal("0.05")));
    }

    @Test
    void resolveBuyerFacingReservePrice_PolicyBound_AppliesCommissionMarkup() {
        // given: 1,000,000 seller-side @ 2% commission -> 1,020,000.00 buyer-facing, exact
        Auction policyBound = new Auction(vehicleId, new BigDecimal("1000000"),
                new BigDecimal("1000000"), null, minBidStep, startTime, endTime, createdBy);
        policyBound.setPolicyVersionId(7L);

        // when
        BigDecimal result = policyBound.resolveBuyerFacingReservePrice(new BigDecimal("0.02"));

        // then
        assertEquals(0, new BigDecimal("1020000.00").compareTo(result));
        assertEquals(2, result.scale());
    }

    @Test
    void resolveBuyerFacingBuyNowPrice_PolicyBound_RoundsHalfUp_UpwardCase() {
        // given: 333,333 seller-side @ 2.5% commission -> 341,666.325 -> HALF_UP -> 341,666.33
        Auction policyBound = new Auction(vehicleId, new BigDecimal("300000"), null,
                new BigDecimal("333333"), minBidStep, startTime, endTime, createdBy);
        policyBound.setPolicyVersionId(7L);

        // when
        BigDecimal result = policyBound.resolveBuyerFacingBuyNowPrice(new BigDecimal("0.025"));

        // then
        assertEquals(0, new BigDecimal("341666.33").compareTo(result));
    }

    @Test
    void resolveBuyerFacingBuyNowPrice_PolicyBound_RoundsHalfUp_DownwardCase() {
        // given: 100 seller-side @ 2.001% commission -> 102.001 -> HALF_UP -> 102.00 (rounds down)
        Auction policyBound = new Auction(vehicleId, new BigDecimal("50"), null,
                new BigDecimal("100"), minBidStep, startTime, endTime, createdBy);
        policyBound.setPolicyVersionId(7L);

        // when
        BigDecimal result = policyBound.resolveBuyerFacingBuyNowPrice(new BigDecimal("0.02001"));

        // then
        assertEquals(0, new BigDecimal("102.00").compareTo(result));
    }

    @Test
    void resolveBuyerFacingReservePrice_PolicyBound_NullCommissionRate_ThrowsNullPointerException() {
        // given: policy-bound auction must never silently default a missing commissionRate
        auction.setPolicyVersionId(1L);

        // when & then
        assertThrows(NullPointerException.class, () -> auction.resolveBuyerFacingReservePrice(null));
    }

    @Test
    void resolveBuyerFacingBuyNowPrice_PolicyBound_NullCommissionRate_ThrowsNullPointerException() {
        auction.setPolicyVersionId(1L);

        assertThrows(NullPointerException.class, () -> auction.resolveBuyerFacingBuyNowPrice(null));
    }

    // ==================== Slice 1B: paymentDeadline ====================

    @Test
    void getPaymentDeadline_Initially_ShouldBeNull() {
        assertNull(auction.getPaymentDeadline());
    }

    @Test
    void setPaymentDeadline_ShouldSetValueWithoutStatusGuard() {
        // given: plain setter, mirrors setConfirmationDeadline - no status requirement, used by
        // AuctionFinalizer (initial assignment) and AuctionMapper (reconstruction)
        OffsetDateTime deadline = OffsetDateTime.now().plusDays(1);

        // when
        auction.setPaymentDeadline(deadline);

        // then
        assertEquals(deadline, auction.getPaymentDeadline());
    }

    @Test
    void extendPaymentDeadline_WhenWaitingForPaymentWithExistingDeadline_ShouldUpdateValue() {
        // given
        OffsetDateTime initialDeadline = OffsetDateTime.now().plusDays(1);
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        auction.setPaymentDeadline(initialDeadline);
        OffsetDateTime newDeadline = initialDeadline.plusDays(3);

        // when
        auction.extendPaymentDeadline(newDeadline);

        // then
        assertEquals(newDeadline, auction.getPaymentDeadline());
        assertNotNull(auction.getUpdatedAt());
    }

    @Test
    void extendPaymentDeadline_AllowsPastDeadline_NoFutureRuleEnforced() {
        // given: locked rule explicitly forbids inventing a "must be future" rule
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        auction.setPaymentDeadline(OffsetDateTime.now().plusDays(1));
        OffsetDateTime pastDeadline = OffsetDateTime.now().minusDays(5);

        // when
        auction.extendPaymentDeadline(pastDeadline);

        // then
        assertEquals(pastDeadline, auction.getPaymentDeadline());
    }

    @Test
    void extendPaymentDeadline_WhenNotWaitingForPayment_ShouldThrowException() {
        // given: CREATED (default setUp state), never reached WAITING_FOR_PAYMENT
        assertThrows(IllegalStateException.class,
                () -> auction.extendPaymentDeadline(OffsetDateTime.now().plusDays(1)));
    }

    @Test
    void extendPaymentDeadline_WhenWaitingForPaymentButNoExistingDeadline_ShouldThrowException() {
        // given: simulates a historical (pre-Slice-1B) WAITING_FOR_PAYMENT auction with no
        // initial deadline - must never be retroactively given one through this path
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        assertNull(auction.getPaymentDeadline());

        // when & then
        assertThrows(IllegalStateException.class,
                () -> auction.extendPaymentDeadline(OffsetDateTime.now().plusDays(1)));
    }

    @Test
    void extendPaymentDeadline_NullNewDeadline_ShouldThrowNullPointerException() {
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        auction.setPaymentDeadline(OffsetDateTime.now().plusDays(1));

        assertThrows(NullPointerException.class, () -> auction.extendPaymentDeadline(null));
    }

    @Test
    void extendPaymentDeadline_WhenSold_ShouldThrowException() {
        // given: deadline changes must never be possible once the auction has left the window
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        auction.setPaymentDeadline(OffsetDateTime.now().plusDays(1));
        auction.markSold();

        assertThrows(IllegalStateException.class,
                () -> auction.extendPaymentDeadline(OffsetDateTime.now().plusDays(2)));
    }
}