package com.example.autoauction.sale.web.dto;

import com.example.autoauction.auction.domain.Auction;

import java.time.OffsetDateTime;

/**
 * Slice 1B: the current payment-deadline state of an auction, including the derived
 * {@code now > paymentDeadline} overdue fact (informational only - never persisted, never
 * acted upon automatically).
 */
public record PaymentDeadlineResponse(
        Long auctionId,
        OffsetDateTime paymentDeadline,
        boolean overdue
) {
    public static PaymentDeadlineResponse fromDomain(Auction auction, OffsetDateTime now) {
        OffsetDateTime deadline = auction.getPaymentDeadline();
        boolean overdue = deadline != null && now.isAfter(deadline);
        return new PaymentDeadlineResponse(auction.getId(), deadline, overdue);
    }
}
