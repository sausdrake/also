package com.example.autoauction.auction.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Slice 1B: StandardPaymentDeadlineCalculator — Asia/Bishkek, end of next working day")
class StandardPaymentDeadlineCalculatorTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bishkek");

    /** Builds a fixed UTC instant corresponding to noon Bishkek time on the given business date. */
    private static OffsetDateTime bishkekNoonInstant(int year, int month, int day) {
        ZonedDateTime businessNoon = ZonedDateTime.of(year, month, day, 12, 0, 0, 0, BUSINESS_ZONE);
        return businessNoon.toOffsetDateTime();
    }

    private static OffsetDateTime expectedDeadline(int year, int month, int day) {
        return ZonedDateTime.of(year, month, day, 18, 0, 0, 0, BUSINESS_ZONE).toOffsetDateTime();
    }

    private StandardPaymentDeadlineCalculator calculatorFixedAt(OffsetDateTime instant) {
        Clock clock = Clock.fixed(instant.toInstant(), ZoneOffset.UTC);
        return new StandardPaymentDeadlineCalculator(clock);
    }

    @Test
    @DisplayName("Monday finalization -> Tuesday 18:00 Asia/Bishkek")
    void monday_ToTuesday() {
        // 2026-09-07 is a Monday
        OffsetDateTime from = bishkekNoonInstant(2026, 9, 7);
        StandardPaymentDeadlineCalculator calc = calculatorFixedAt(from);

        assertThat(calc.computeInitialDeadline()).isEqualTo(expectedDeadline(2026, 9, 8));
    }

    @Test
    @DisplayName("Thursday finalization -> Friday 18:00")
    void thursday_ToFriday() {
        // 2026-09-10 is a Thursday
        OffsetDateTime from = bishkekNoonInstant(2026, 9, 10);
        StandardPaymentDeadlineCalculator calc = calculatorFixedAt(from);

        assertThat(calc.computeInitialDeadline()).isEqualTo(expectedDeadline(2026, 9, 11));
    }

    @Test
    @DisplayName("Friday finalization -> Monday 18:00 (skips the weekend)")
    void friday_ToMonday() {
        // 2026-09-11 is a Friday
        OffsetDateTime from = bishkekNoonInstant(2026, 9, 11);
        StandardPaymentDeadlineCalculator calc = calculatorFixedAt(from);

        assertThat(calc.computeInitialDeadline()).isEqualTo(expectedDeadline(2026, 9, 14));
    }

    @Test
    @DisplayName("Saturday finalization -> Monday 18:00")
    void saturday_ToMonday() {
        // 2026-09-12 is a Saturday
        OffsetDateTime from = bishkekNoonInstant(2026, 9, 12);
        StandardPaymentDeadlineCalculator calc = calculatorFixedAt(from);

        assertThat(calc.computeInitialDeadline()).isEqualTo(expectedDeadline(2026, 9, 14));
    }

    @Test
    @DisplayName("Sunday finalization -> Monday 18:00")
    void sunday_ToMonday() {
        // 2026-09-13 is a Sunday
        OffsetDateTime from = bishkekNoonInstant(2026, 9, 13);
        StandardPaymentDeadlineCalculator calc = calculatorFixedAt(from);

        assertThat(calc.computeInitialDeadline()).isEqualTo(expectedDeadline(2026, 9, 14));
    }

    @Test
    @DisplayName("A holiday falling Mon-Fri is still treated as a working day (no holiday calendar in this slice)")
    void holidayOnWeekday_StillCountsAsWorkingDay() {
        // 2026-09-07 (Monday) finalization still deadlines on Tuesday 2026-09-08, regardless of
        // whether that Tuesday happens to be a public holiday - no holiday calendar exists.
        OffsetDateTime from = bishkekNoonInstant(2026, 9, 7);
        StandardPaymentDeadlineCalculator calc = calculatorFixedAt(from);

        assertThat(calc.computeInitialDeadline()).isEqualTo(expectedDeadline(2026, 9, 8));
    }

    @Test
    @DisplayName("Source instant given in UTC is correctly interpreted in Asia/Bishkek business time")
    void explicitTimezoneConversion_UtcSourceInstant() {
        // 2026-09-10 18:30 UTC == 2026-09-11 00:30 Asia/Bishkek (UTC+6) - already past midnight
        // into Friday business-local, so the next working day is Saturday->Monday.
        Instant utcInstant = Instant.parse("2026-09-10T18:30:00Z");
        Clock clock = Clock.fixed(utcInstant, ZoneOffset.UTC);
        StandardPaymentDeadlineCalculator calc = new StandardPaymentDeadlineCalculator(clock);

        // business-local date is 2026-09-11 (Friday) -> next working day is Monday 2026-09-14
        assertThat(calc.computeInitialDeadline()).isEqualTo(expectedDeadline(2026, 9, 14));
    }

    @Test
    @DisplayName("Deterministic under Clock.fixed - repeated calls return the identical instant")
    void deterministicUnderFixedClock() {
        OffsetDateTime from = bishkekNoonInstant(2026, 9, 7);
        StandardPaymentDeadlineCalculator calc = calculatorFixedAt(from);

        OffsetDateTime first = calc.computeInitialDeadline();
        OffsetDateTime second = calc.computeInitialDeadline();

        assertThat(first).isEqualTo(second);
    }
}
