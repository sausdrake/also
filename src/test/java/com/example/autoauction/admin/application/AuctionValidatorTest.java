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
    private static final BigDecimal STEP = new BigDecimal("10.00");

    private final AuctionValidator validator = new AuctionValidator(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

    private static AuctionCreateRequest request(Long vehicleId, BigDecimal startingPrice, BigDecimal reservePrice,
                                                BigDecimal buyNowPrice, BigDecimal minBidStep,
                                                OffsetDateTime startTime, OffsetDateTime endTime) {
        return new AuctionCreateRequest(vehicleId, startingPrice, reservePrice, buyNowPrice, minBidStep,
                startTime, endTime, null, null);
    }

    private static AuctionCreateRequest valid() {
        return request(1L, START_PRICE, null, null, STEP, START, START.plusHours(2));
    }

    private static AuctionCreateRequest withPrices(BigDecimal start, BigDecimal reserve, BigDecimal buyNow,
                                                   BigDecimal step) {
        return request(1L, start, reserve, buyNow, step, START, START.plusHours(2));
    }

    private static AuctionCreateRequest withTimes(OffsetDateTime start, OffsetDateTime end) {
        return request(1L, START_PRICE, null, null, STEP, start, end);
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
                request(1L, START_PRICE, null, null, STEP, START, START.plusHours(2))));
    }

    @Test
    void basicRequest_nullVehicleId_throwsNpe() {
        var r = request(null, START_PRICE, null, null, STEP, START, START.plusHours(2));
        assertThrows(NullPointerException.class, () -> validator.validateBasicRequest(r));
    }

    @Test
    void basicRequest_nullStartingPrice_throwsNpe() {
        var r = request(1L, null, null, null, STEP, START, START.plusHours(2));
        assertThrows(NullPointerException.class, () -> validator.validateBasicRequest(r));
    }

    @Test
    void basicRequest_nullMinBidStep_throwsNpe() {
        var r = request(1L, START_PRICE, null, null, null, START, START.plusHours(2));
        assertThrows(NullPointerException.class, () -> validator.validateBasicRequest(r));
    }

    @Test
    void basicRequest_nullStartTime_throwsNpe() {
        var r = request(1L, START_PRICE, null, null, STEP, null, START.plusHours(2));
        assertThrows(NullPointerException.class, () -> validator.validateBasicRequest(r));
    }

    @Test
    void basicRequest_nullEndTime_throwsNpe() {
        var r = request(1L, START_PRICE, null, null, STEP, START, null);
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
        assertRejected(withPrices(BigDecimal.ZERO, null, null, STEP));
    }

    @Test
    void startingPrice_negative_rejected() {
        assertRejected(withPrices(new BigDecimal("-1.00"), null, null, STEP));
    }

    @Test
    void startingPrice_smallestPositive_accepted() {
        assertAccepted(withPrices(new BigDecimal("0.01"), null, null, new BigDecimal("0.01")));
    }

    @Test
    void reservePrice_zero_rejected() {
        assertRejected(withPrices(START_PRICE, BigDecimal.ZERO, null, STEP));
    }

    @Test
    void reservePrice_negative_rejected() {
        assertRejected(withPrices(START_PRICE, new BigDecimal("-5.00"), null, STEP));
    }

    @Test
    void reservePrice_belowStartingPrice_rejected() {
        assertRejected(withPrices(START_PRICE, new BigDecimal("999.99"), null, STEP));
    }

    @Test
    void reservePrice_equalToStartingPrice_accepted() {
        assertAccepted(withPrices(START_PRICE, START_PRICE, null, STEP));
    }

    @Test
    void reservePrice_aboveStartingPrice_accepted() {
        assertAccepted(withPrices(START_PRICE, new BigDecimal("1000.01"), null, STEP));
    }

    @Test
    void buyNowPrice_zero_rejected() {
        assertRejected(withPrices(START_PRICE, null, BigDecimal.ZERO, STEP));
    }

    @Test
    void buyNowPrice_negative_rejected() {
        assertRejected(withPrices(START_PRICE, null, new BigDecimal("-1.00"), STEP));
    }

    @Test
    void buyNowPrice_zeroWithValidReserve_rejected() {
        assertRejected(withPrices(START_PRICE, new BigDecimal("1200.00"), BigDecimal.ZERO, STEP));
    }

    @Test
    void buyNowPrice_belowStartingPrice_rejected() {
        assertRejected(withPrices(START_PRICE, null, new BigDecimal("999.99"), STEP));
    }

    @Test
    void buyNowPrice_equalToStartingPrice_rejected() {
        assertRejected(withPrices(START_PRICE, null, START_PRICE, STEP));
    }

    @Test
    void buyNowPrice_aboveStartingPriceByOneCent_accepted() {
        assertAccepted(withPrices(START_PRICE, null, new BigDecimal("1000.01"), STEP));
    }

    @Test
    void reserveAndBuyNow_bothValid_accepted() {
        assertAccepted(withPrices(START_PRICE, new BigDecimal("1200.00"), new BigDecimal("1500.00"), STEP));
    }

    // ---- bid step ----

    @Test
    void bidStep_zero_rejected() {
        assertRejected(withPrices(START_PRICE, null, null, BigDecimal.ZERO));
    }

    @Test
    void bidStep_negative_rejected() {
        assertRejected(withPrices(START_PRICE, null, null, new BigDecimal("-10.00")));
    }

    @Test
    void bidStep_exactlyOnePercentOfStartingPrice_accepted() {
        assertAccepted(withPrices(START_PRICE, null, null, new BigDecimal("10.00")));
    }

    @Test
    void bidStep_justBelowOnePercent_rejected() {
        assertRejected(withPrices(START_PRICE, null, null, new BigDecimal("9.99")));
    }

    @Test
    void bidStep_comparisonIsScaleInsensitive() {
        assertAccepted(withPrices(START_PRICE, null, null, new BigDecimal("10")));
        assertAccepted(withPrices(START_PRICE, null, null, new BigDecimal("10.000")));
    }

    @Test
    void bidStep_largeStep_accepted() {
        assertAccepted(withPrices(START_PRICE, null, null, new BigDecimal("5000.00")));
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
