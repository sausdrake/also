package com.example.autoauction.sale.web.dto;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Slice 1B: PaymentDeadlineResponse — derived overdue predicate (now > paymentDeadline)")
class PaymentDeadlineResponseTest {

    private Auction auctionWithDeadline(OffsetDateTime deadline) {
        Auction auction = new Auction(1L, new BigDecimal("1000000"), null, null,
                new BigDecimal("10000"), OffsetDateTime.now().minusDays(7), OffsetDateTime.now().minusHours(1), 10L);
        auction.setId(1L);
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        auction.setPaymentDeadline(deadline);
        return auction;
    }

    @Test
    @DisplayName("now before deadline -> overdue = false")
    void now_BeforeDeadline_NotOverdue() {
        OffsetDateTime deadline = OffsetDateTime.parse("2026-09-15T18:00:00+06:00");
        OffsetDateTime now = deadline.minusHours(1);

        PaymentDeadlineResponse response = PaymentDeadlineResponse.fromDomain(auctionWithDeadline(deadline), now);

        assertThat(response.overdue()).isFalse();
    }

    @Test
    @DisplayName("now exactly equal to deadline -> overdue = false (equality is NOT overdue)")
    void now_ExactlyAtDeadline_NotOverdue() {
        OffsetDateTime deadline = OffsetDateTime.parse("2026-09-15T18:00:00+06:00");

        PaymentDeadlineResponse response = PaymentDeadlineResponse.fromDomain(auctionWithDeadline(deadline), deadline);

        assertThat(response.overdue()).isFalse();
    }

    @Test
    @DisplayName("now after deadline -> overdue = true")
    void now_AfterDeadline_Overdue() {
        OffsetDateTime deadline = OffsetDateTime.parse("2026-09-15T18:00:00+06:00");
        OffsetDateTime now = deadline.plusSeconds(1);

        PaymentDeadlineResponse response = PaymentDeadlineResponse.fromDomain(auctionWithDeadline(deadline), now);

        assertThat(response.overdue()).isTrue();
    }

    @Test
    @DisplayName("null paymentDeadline -> overdue = false (never true for an auction with no deadline)")
    void nullDeadline_NeverOverdue() {
        PaymentDeadlineResponse response = PaymentDeadlineResponse.fromDomain(
                auctionWithDeadline(null), OffsetDateTime.now());

        assertThat(response.overdue()).isFalse();
        assertThat(response.paymentDeadline()).isNull();
    }
}
