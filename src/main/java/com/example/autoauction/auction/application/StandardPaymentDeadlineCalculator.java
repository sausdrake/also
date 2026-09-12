package com.example.autoauction.auction.application;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Slice 1B: the single, narrow responsibility of computing the standard initial payment
 * deadline - "end of the next working day, Asia/Bishkek business time" (locked business rule).
 * No persistence, no admin logic, no history logic, no Auction/status logic - a pure
 * instant-in/instant-out calculation, deliberately not a generic {@code DateUtils}/
 * {@code TimeUtils}.
 *
 * <p>"Next working day" means the next calendar day strictly after the source instant's
 * business-local date that is not Saturday/Sunday - a working day today does not count, matching
 * the locked examples (a Monday finalization deadlines on Tuesday, not the same Monday). Public
 * holidays are ignored for MVP (locked rule) - a holiday falling Mon-Fri is still a working day.
 *
 * <p>Uses the shared {@code Clock} bean exclusively, never {@code OffsetDateTime.now()} - matches
 * this codebase's established determinism convention (see {@code AuctionFinalizer},
 * {@code SettlementService}).
 */
@Service
public class StandardPaymentDeadlineCalculator {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bishkek");
    private static final LocalTime END_OF_BUSINESS_DAY = LocalTime.of(18, 0);

    private final Clock clock;

    public StandardPaymentDeadlineCalculator(Clock clock) {
        this.clock = clock;
    }

    /** Computes the standard deadline from the current shared-Clock instant. */
    public OffsetDateTime computeInitialDeadline() {
        return computeInitialDeadline(OffsetDateTime.now(clock));
    }

    /**
     * Pure computation from an explicit instant - exposed for direct, Clock-free unit testing of
     * the working-day/timezone arithmetic itself.
     */
    OffsetDateTime computeInitialDeadline(OffsetDateTime from) {
        ZonedDateTime businessNow = from.atZoneSameInstant(BUSINESS_ZONE);
        LocalDate nextWorkingDay = nextWorkingDay(businessNow.toLocalDate());
        return nextWorkingDay.atTime(END_OF_BUSINESS_DAY).atZone(BUSINESS_ZONE).toOffsetDateTime();
    }

    /**
     * The next calendar day strictly after {@code date} that is Monday-Friday - always advances
     * at least one day, then skips Saturday/Sunday.
     */
    private LocalDate nextWorkingDay(LocalDate date) {
        LocalDate candidate = date.plusDays(1);
        while (candidate.getDayOfWeek() == DayOfWeek.SATURDAY || candidate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }
}
