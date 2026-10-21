package com.example.autoauction.admin.application;

import com.example.autoauction.auction.web.dto.AuctionCreateRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Characterization tests for the auction-creation rules of {@link AuctionValidator}.
 * They record current behaviour only. The MIN_HOURS_BEFORE_START check in validateTime
 * is unreachable (a start in the past is rejected earlier) and is therefore not covered.
 */
class AuctionValidatorTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2030-01-01T00:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);
    private static final OffsetDateTime START = NOW.plusHours(1);
    private static final BigDecimal START_PRICE = new BigDecimal("1000.00");

    private final AuctionValidator validator = new AuctionValidator(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

    private static AuctionCreateRequest request(Long vehicleId, BigDecimal startingPrice, BigDecimal reservePrice,
                                                BigDecimal buyNowPrice,
                                                OffsetDateTime startTime, OffsetDateTime endTime) {
        return new AuctionCreateRequest(vehicleId, startingPrice, reservePrice, buyNowPrice,
                startTime, endTime, null, null);
    }

    private static AuctionCreateRequest valid() {
        return request(1L, START_PRICE, null, null, START, START.plusHours(2));
    }

    private static AuctionCreateRequest withPrices(BigDecimal start, BigDecimal reserve, BigDecimal buyNow) {
        return request(1L, start, reserve, buyNow, START, START.plusHours(2));
    }

    private static AuctionCreateRequest withTimes(OffsetDateTime start, OffsetDateTime end) {
        return request(1L, START_PRICE, null, null, start, end);
    }

    private void assertRejected(AuctionCreateRequest request) {
        assertThrows(IllegalArgumentException.class, () -> validator.validateBusinessRules(request, null));
    }

    private void assertAccepted(AuctionCreateRequest request) {
        assertDoesNotThrow(() -> validator.validateBusinessRules(request, null));
    }

    // ---- validateBasicRequest ----

    @Test
    void basicRequest_valid_passes() {
        assertDoesNotThrow(() -> validator.validateBasicRequest(valid()));
    }

    @Test
    void basicRequest_optionalPricesNull_passes() {
        assertDoesNotThrow(() -> validator.validateBasicRequest(
                request(1L, START_PRICE, null, null, START, START.plusHours(2))));
    }

    @Test
    void basicRequest_nullVehicleId_throwsNpe() {
        var r = request(null, START_PRICE, null, null, START, START.plusHours(2));
        assertThrows(NullPointerException.class, () -> validator.validateBasicRequest(r));
    }

    @Test
    void basicRequest_nullStartingPrice_throwsNpe() {
        var r = request(1L, null, null, null, START, START.plusHours(2));
        assertThrows(NullPointerException.class, () -> validator.validateBasicRequest(r));
    }

    @Test
    void basicRequest_nullStartTime_throwsNpe() {
        var r = request(1L, START_PRICE, null, null, null, START.plusHours(2));
        assertThrows(NullPointerException.class, () -> validator.validateBasicRequest(r));
    }

    @Test
    void basicRequest_nullEndTime_throwsNpe() {
        var r = request(1L, START_PRICE, null, null, START, null);
        assertThrows(NullPointerException.class, () -> validator.validateBasicRequest(r));
    }

    // ---- validateBusinessRules: baseline ----

    @Test
    void businessRules_validRequest_nullVehicle_passes() {
        assertAccepted(valid());
    }

    // ---- prices ----

    @Test
    void startingPrice_zero_rejected() {
        assertRejected(withPrices(BigDecimal.ZERO, null, null));
    }

    @Test
    void startingPrice_negative_rejected() {
        assertRejected(withPrices(new BigDecimal("-1.00"), null, null));
    }

    @Test
    void startingPrice_oneTyiyn_rejectedBecauseItsStepRoundsToZero() {
        // AUCTION-01: the derived step of 0.01 is 0.0001 -> 0.000; the "step > 0" rule still holds
        assertRejected(withPrices(new BigDecimal("0.01"), null, null));
    }

    @Test
    void reservePrice_zero_rejected() {
        assertRejected(withPrices(START_PRICE, BigDecimal.ZERO, null));
    }

    @Test
    void reservePrice_negative_rejected() {
        assertRejected(withPrices(START_PRICE, new BigDecimal("-5.00"), null));
    }

    @Test
    void reservePrice_belowStartingPrice_rejected() {
        assertRejected(withPrices(START_PRICE, new BigDecimal("999.99"), null));
    }

    @Test
    void reservePrice_equalToStartingPrice_accepted() {
        assertAccepted(withPrices(START_PRICE, START_PRICE, null));
    }

    @Test
    void reservePrice_aboveStartingPrice_accepted() {
        assertAccepted(withPrices(START_PRICE, new BigDecimal("1000.01"), null));
    }

    @Test
    void buyNowPrice_zero_rejected() {
        assertRejected(withPrices(START_PRICE, null, BigDecimal.ZERO));
    }

    @Test
    void buyNowPrice_negative_rejected() {
        assertRejected(withPrices(START_PRICE, null, new BigDecimal("-1.00")));
    }

    @Test
    void buyNowPrice_zeroWithValidReserve_rejected() {
        assertRejected(withPrices(START_PRICE, new BigDecimal("1200.00"), BigDecimal.ZERO));
    }

    @Test
    void buyNowPrice_belowStartingPrice_rejected() {
        assertRejected(withPrices(START_PRICE, null, new BigDecimal("999.99")));
    }

    @Test
    void buyNowPrice_equalToStartingPrice_rejected() {
        assertRejected(withPrices(START_PRICE, null, START_PRICE));
    }

    @Test
    void buyNowPrice_aboveStartingPriceByOneCent_accepted() {
        assertAccepted(withPrices(START_PRICE, null, new BigDecimal("1000.01")));
    }

    @Test
    void reserveAndBuyNow_bothValid_accepted() {
        assertAccepted(withPrices(START_PRICE, new BigDecimal("1200.00"), new BigDecimal("1500.00")));
    }

    // ---- bid step (AUCTION-01, BD-02: derived as ROUND(startingPrice x 0.01, 3), never entered) ----

    @Test
    void bidStep_priceWhoseStepRoundsToZero_rejected() {
        assertRejected(withPrices(new BigDecimal("0.04"), null, null));   // 0.0004 -> 0.000
    }

    @Test
    void bidStep_smallestPriceWithNonZeroStep_accepted() {
        assertAccepted(withPrices(new BigDecimal("0.05"), null, null));   // 0.0005 -> 0.001 (half-up)
    }

    // ---- time ----

    @Test
    void startTime_equalToNow_accepted() {
        assertAccepted(withTimes(NOW, NOW.plusHours(2)));
    }

    @Test
    void startTime_oneNanoBeforeNow_rejected() {
        OffsetDateTime start = NOW.minusNanos(1);
        assertRejected(withTimes(start, start.plusHours(2)));
    }

    @Test
    void startTime_farInPast_rejected() {
        OffsetDateTime start = NOW.minusDays(1);
        assertRejected(withTimes(start, start.plusHours(2)));
    }

    @Test
    void endTime_equalToStartTime_rejected() {
        assertRejected(withTimes(START, START));
    }

    @Test
    void endTime_beforeStartTime_rejected() {
        assertRejected(withTimes(START, START.minusHours(2)));
    }

    @Test
    void duration_exactlyOneHour_accepted() {
        assertAccepted(withTimes(START, START.plus(Duration.ofMinutes(60))));
    }

    @Test
    void duration_fiftyNineMinutes_rejected() {
        assertRejected(withTimes(START, START.plus(Duration.ofMinutes(59))));
    }

    @Test
    void duration_fiftyNineMinutesFiftyNineSeconds_rejected() {
        assertRejected(withTimes(START, START.plus(Duration.ofMinutes(59).plusSeconds(59))));
    }

    @Test
    void duration_exactlyThirtyDays_accepted() {
        assertAccepted(withTimes(START, START.plus(Duration.ofDays(30))));
    }

    @Test
    void duration_thirtyDaysPlusOneMinute_rejected() {
        assertRejected(withTimes(START, START.plus(Duration.ofDays(30).plusMinutes(1))));
    }

    @Test
    void duration_thirtyDaysPlusFiftyNineSeconds_acceptedDueToMinuteTruncation() {
        // Quirk: the duration is truncated to whole minutes, so 30d + 59s counts as exactly 30d.
        assertAccepted(withTimes(START, START.plus(Duration.ofDays(30).plusSeconds(59))));
    }

    @Test
    void times_areComparedAsInstantsAcrossOffsets() {
        OffsetDateTime start = START.withOffsetSameInstant(ZoneOffset.ofHours(7));
        OffsetDateTime end = START.plusHours(2).withOffsetSameInstant(ZoneOffset.ofHours(-5));
        assertAccepted(withTimes(start, end));
    }
}
