// deposit/application/DepositReleasedEvent.java
package com.example.autoauction.deposit.application;

import java.math.BigDecimal;

/**
 * Published by {@link DepositService#releaseWinnerDeposit} once the winner's deposit release has
 * been mutated and saved, and consumed only after the surrounding transaction has actually
 * committed (see {@link DepositReleasedEventListener}).
 *
 * releaseWinnerDeposit has two production callers - {@code AuctionFinalizer.completeSale} and
 * {@code AdminAuctionService.releaseWinner} - each with a different enclosing transaction shape;
 * publishing from inside the deposit mutation itself (rather than from either caller) is what
 * lets AFTER_COMMIT correctly bind to whichever transaction actually ends up committing, for
 * both callers uniformly (G2).
 *
 * Deliberately minimal - mirrors {@link DepositFrozenEvent}'s three-primitive shape exactly, since
 * the DEPOSIT_RELEASED notification's content is fully expressed by these three fields.
 */
public record DepositReleasedEvent(
        Long userId,
        Long auctionId,
        BigDecimal amount
) {
}
