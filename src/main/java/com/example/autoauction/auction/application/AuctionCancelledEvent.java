// auction/application/AuctionCancelledEvent.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;

import java.util.List;

/**
 * Published by {@link AuctionFinalizer#cancelAuction} once the cancellation has been applied and
 * saved, and consumed only after the surrounding transaction has actually committed (see
 * {@link AuctionCancelledEventListener}). {@code auction} is the already-materialized, post-save
 * domain snapshot - not a JPA-managed/lazy reference - so it stays safe to read after that
 * transaction's persistence context has closed.
 *
 * {@code participantIds} is the original participant list, captured before
 * {@code participantRepository.clearParticipants()} runs, since the listener has no way to
 * re-query it after that point. No winner/winningBid/sellerId field exists here -
 * {@code cancelAuction} never computes or notifies on any of those, unlike
 * {@link AuctionFinalizedEvent}'s winner/no-winner shape.
 */
public record AuctionCancelledEvent(
        Auction auction,
        List<Long> participantIds
) {
}
