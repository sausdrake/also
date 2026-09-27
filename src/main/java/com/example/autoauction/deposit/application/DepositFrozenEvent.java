// deposit/application/DepositFrozenEvent.java
package com.example.autoauction.deposit.application;

import java.math.BigDecimal;

/**
 * Published by {@link DepositService#freezeForAuction} once the deposit freeze has been mutated
 * and saved, and consumed only after the surrounding transaction has actually committed (see
 * {@link DepositFrozenEventListener}).
 *
 * Deliberately minimal - no Deposit/Auction domain reference is needed: the DEPOSIT_FROZEN
 * notification's content is fully expressed by these three primitives, so none of the auction
 * module's event conventions (a held domain snapshot for downstream fields like vehicleInfo) are
 * copied here without need.
 */
public record DepositFrozenEvent(
        Long userId,
        Long auctionId,
        BigDecimal requiredAmount
) {
}
