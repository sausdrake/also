// auction/application/BuyNowFinalizedEvent.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;

import java.math.BigDecimal;

/**
 * Published by {@link AuctionFinalizer#finalizeWithBuyNow} once the Buy Now sale has been applied
 * and saved, and consumed only after the surrounding transaction has actually committed (see
 * {@link BuyNowFinalizedEventListener}). {@code auction} is the already-materialized, post-save
 * domain snapshot (status WAITING_FOR_PAYMENT, winner/price fields set) - not a JPA-managed/lazy
 * reference - so it stays safe to read after that transaction's persistence context has closed.
 *
 * Deliberately not a reuse of {@link BidPlacedEvent}: the payload shape (no prior leader, no
 * extension flag, a single winner rather than a bidder-vs-leader distinction) and the resulting
 * notification content (AUCTION_WON, not OUTBID/NEW_BID_ON_VEHICLE/AUCTION_LEADER) are materially
 * different (see .auto-dev/mvp-002-plan.md §3).
 */
public record BuyNowFinalizedEvent(
        Auction auction,
        Long winnerId,
        BigDecimal buyNowAmount
) {
}
