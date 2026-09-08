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
}