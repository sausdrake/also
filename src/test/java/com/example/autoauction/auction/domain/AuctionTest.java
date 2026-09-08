package com.example.autoauction.auction.domain;

import org.junit.jupiter.api.BeforeEach;
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
}