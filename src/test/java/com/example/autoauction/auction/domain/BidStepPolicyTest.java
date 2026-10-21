package com.example.autoauction.auction.domain;

import com.example.autoauction.auction.web.dto.AuctionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AUCTION-01 (BD-02..BD-04): minimumBidStep = ROUND(startingPrice x 0.01, 3), half-up; the first bid must be
 * {@code >= startingPrice + step}; a bid is money (2 decimals), so the smallest valid bid is that threshold
 * rounded up to the tyiyn.
 */
class BidStepPolicyTest {

    @Nested
    @DisplayName("minimumBidStep: rounding matrix")
    class Step {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "100000, 1000.000",
                "123456, 1234.560",
                "1234567, 12345.670",      // BD-02 example
                "123.4567, 1.235",         // BD-02 example: 1.234567 -> 1.235
                "999999.99, 10000.000",    // 9999.9999 -> carry
                "1000000, 10000.000",
                "0.05, 0.001",             // 0.0005: exactly half -> up
                "0.0499, 0.000",           // 0.000499 -> 0.000: a zero step is refused
                "123.4449999, 1.234",      // .xxx4449999 -> down
                "123.44999, 1.234",        // .xxx499 -> down
                "123.45, 1.235",           // .xxx500 -> up (mathematical, not banker's)
                "123.46, 1.235",
                "123.35, 1.234",           // 1.2335 -> up
                "123.25, 1.233",           // 1.2325 -> HALF_UP 1.233, HALF_EVEN 1.232
                "99999999.99, 1000000.000" // technical capacity of the price column
        })
        void derivedStep(String startingPrice, String expected) {
            if (new BigDecimal(expected).signum() == 0) {
                assertThrows(IllegalArgumentException.class,
                        () -> BidStepPolicy.minimumBidStep(new BigDecimal(startingPrice)));
                return;
            }
            BigDecimal step = BidStepPolicy.minimumBidStep(new BigDecimal(startingPrice));
            assertEquals(new BigDecimal(expected), step);   // value AND scale 3
            assertEquals(3, step.scale());
        }

        @Test
        void notTruncation() {
            // truncation would give 12345.67 for 1234567.99 x 0.01 = 12345.6799
            assertEquals(new BigDecimal("12345.680"), BidStepPolicy.minimumBidStep(new BigDecimal("1234567.99")));
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-1", "-0.01", "0.01", "0.04", "0.0499"})
        void zeroNegativeOrStepRoundingToZero_refused(String price) {
            assertThrows(IllegalArgumentException.class, () -> BidStepPolicy.minimumBidStep(new BigDecimal(price)));
        }

        @Test
        void nullRefused() {
            assertThrows(NullPointerException.class, () -> BidStepPolicy.minimumBidStep(null));
        }
    }

    @Nested
    @DisplayName("money scale")
    class Money {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "124.695, 124.70",
                "124.69, 124.69",
                "124.6900, 124.69",
                "124.691, 124.70",
                "100, 100.00",
                "1246912.67, 1246912.67"
        })
        void smallestValidBid_roundsUpToTheTyiyn(String threshold, String expected) {
            assertEquals(new BigDecimal(expected), BidStepPolicy.smallestValidBid(new BigDecimal(threshold)));
        }

        @ParameterizedTest
        @ValueSource(strings = {"1", "1.2", "1.23", "1.230", "1.2300000", "1E+3", "99999999.99"})
        void isMoney_true(String v) {
            assertTrue(BidStepPolicy.isMoney(new BigDecimal(v)));
        }

        @ParameterizedTest
        @ValueSource(strings = {"1.231", "0.001", "124.685", "1.2345678"})
        void isMoney_false(String v) {
            assertFalse(BidStepPolicy.isMoney(new BigDecimal(v)));
        }

        @Test
        void isMoney_null_false() {
            assertFalse(BidStepPolicy.isMoney(null));
        }

        @ParameterizedTest
        @CsvSource({
                "0.01, true", "99999999.99, true", "100000000, false", "99999999.991, false",
                "0, false", "-0.01, false", "1.234, false"
        })
        void isStorableMoney(String v, boolean expected) {
            assertEquals(expected, BidStepPolicy.isStorableMoney(new BigDecimal(v)));
        }
    }

    @Nested
    @DisplayName("Auction.addBid with a 3-decimal step (BD-03 / BD-04)")
    class Bids {

        private Auction auctionAt(String startingPrice) {
            BigDecimal start = new BigDecimal(startingPrice);
            OffsetDateTime begin = OffsetDateTime.now().plusDays(1);
            Auction auction = new Auction(1L, start, null, null, BidStepPolicy.minimumBidStep(start),
                    begin, begin.plusDays(7), 1L);
            auction.start();
            return auction;
        }

        @Test
        void firstBid_boundaries() {
            // 123.45 -> step 1.235 -> threshold 124.685 -> smallest valid bid 124.69
            Auction a = auctionAt("123.45");
            assertEquals(new BigDecimal("124.685"), a.minimumNextBid());
            assertThrows(IllegalArgumentException.class, () -> a.addBid(new BigDecimal("123.45"), 2L)); // = starting price
            assertThrows(IllegalArgumentException.class, () -> a.addBid(new BigDecimal("124.68"), 2L)); // one tyiyn below
            assertThrows(IllegalArgumentException.class, () -> a.addBid(new BigDecimal("124.685"), 2L)); // exact, but not money
            a.addBid(new BigDecimal("124.69"), 2L);
            assertEquals(new BigDecimal("124.69"), a.getCurrentPrice());
        }

        @Test
        void exactThresholdThatIsMoney_accepted() {
            Auction a = auctionAt("100000");   // step 1000.000 -> threshold 101000.000
            assertThrows(IllegalArgumentException.class, () -> a.addBid(new BigDecimal("100999.99"), 2L));
            a.addBid(new BigDecimal("101000.00"), 2L);
            assertEquals(1, a.getTotalBids());
        }

        @Test
        void nextBid_usesTheAuctionsOwnStepOverTheNewPrice() {
            Auction a = auctionAt("123.45");
            a.addBid(new BigDecimal("124.69"), 2L);
            // 124.69 + 1.235 = 125.925 -> 125.93
            assertThrows(IllegalArgumentException.class, () -> a.addBid(new BigDecimal("125.92"), 3L));
            a.addBid(new BigDecimal("125.93"), 3L);
            assertEquals(Long.valueOf(3L), a.getWinnerId());
        }

        @Test
        void trailingZerosAreMoney() {
            Auction a = auctionAt("123.45");
            a.addBid(new BigDecimal("124.6900"), 2L);
            assertEquals(1, a.getTotalBids());
        }

        @Test
        void tooLowMessage_namesTheSmallestValidBid() {
            Auction a = auctionAt("123.45");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> a.addBid(new BigDecimal("124.00"), 2L));
            assertTrue(e.getMessage().contains("124.69"), e.getMessage());
        }

        @Test
        void refusedBidChangesNothing() {
            Auction a = auctionAt("123.45");
            assertThrows(IllegalArgumentException.class, () -> a.addBid(new BigDecimal("124.689"), 2L));
            assertEquals(new BigDecimal("123.45"), a.getCurrentPrice());
            assertEquals(0, a.getTotalBids());
            assertNull(a.getWinnerId());
        }

        @Test
        void response_minimumNextBid_isTheServerDerivedSmallestValidBid() {
            Auction a = auctionAt("123.45");
            assertEquals(new BigDecimal("124.69"), AuctionResponse.fromDomain(a, null).minimumNextBid());
            a.addBid(new BigDecimal("124.69"), 2L);
            assertEquals(new BigDecimal("125.93"), AuctionResponse.fromDomain(a, null).minimumNextBid());
        }
    }
}
