// auction/application/AuctionFinalizedEvent.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;

import java.math.BigDecimal;
import java.util.List;

/**
 * Published by {@link AuctionFinalizer#finalizeAuction} once a naturally-ended auction (expired
 * with no bids, expired with reserve not met, or concluded with a winner) has been mutated and
 * saved, and consumed only after the surrounding transaction has actually committed (see
 * {@link AuctionFinalizedEventListener}). {@code auction} is the already-materialized, post-save
 * domain snapshot - not a JPA-managed/lazy reference - so it stays safe to read after that
 * transaction's persistence context has closed.
 *
 * {@code winnerId}/{@code winningBid} are null for the two no-winner branches (no bids, reserve
 * not met) - the same nullable shape {@code finalizeAuction} already uses internally for these
 * two local variables, not an artificially introduced discriminator. {@code participantIds} is
 * the original participant list (winner + losers), captured before
 * {@code participantRepository.clearParticipants()} runs, since the listener has no way to
 * re-query it after that point.
 */
public record AuctionFinalizedEvent(
        Auction auction,
        Long winnerId,
        BigDecimal winningBid,
        List<Long> participantIds
) {
}
