// auction/application/BidPlacedEvent.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;

import java.math.BigDecimal;

/**
 * Published by {@link BidService#placeBid} once the bid has been applied and saved, and consumed
 * only after the surrounding transaction has actually committed (see {@link BidPlacedEventListener}).
 * {@code auction} is the already-materialized domain snapshot returned by the repository save -
 * not a JPA-managed/lazy reference - so it stays safe to read after that transaction's persistence
 * context has closed.
 */
public record BidPlacedEvent(
        Auction auction,
        Long bidderId,
        BigDecimal bidAmount,
        Long prevLeaderId,
        boolean auctionExtended
) {
}
