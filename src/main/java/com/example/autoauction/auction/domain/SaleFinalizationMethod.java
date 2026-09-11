package com.example.autoauction.auction.domain;

/**
 * Step 2C: persisted discriminator distinguishing how an Auction's winner/winningBid were
 * determined - needed because the buyer-facing Buy Now amount cannot be safely reverse-engineered
 * as an ordinary auction bid (the forward markup rounding is not reliably invertible). {@code
 * null} on {@link Auction#getSaleFinalizationMethod()} means a historical auction finalized
 * before this discriminator existed - Settlement creation must fail closed for such auctions,
 * never guess the method from price or any other inference.
 */
public enum SaleFinalizationMethod {
    /** Winner determined through ordinary competitive bidding. */
    ORDINARY_BID,
    /** Winner determined through an immediate Buy Now purchase. */
    BUY_NOW
}
