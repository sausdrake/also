// deposit/application/DepositFrozenEventListener.java
package com.example.autoauction.deposit.application;

import com.example.autoauction.notification.application.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fires only after {@link DepositService#freezeForAuction}'s transaction has physically
 * committed - a rolled-back freeze (e.g. a genuine optimistic-lock conflict on the deposit's own
 * @Version, already proven reproducible by {@code BidServiceRetryConcurrencyPgIntegrationTest}
 * (D5-R), or a later failure in the caller's own transaction, e.g. BidService.buyNow's
 * finalizeWithBuyNow) never reaches this listener at all, so it can no longer leak or duplicate a
 * live notification for a freeze whose database effects were discarded (G1, mirroring MVP-001's
 * BidPlacedEventListener and the other auction.application event/listener pairs).
 *
 * This is now the SOLE dispatch path for the DEPOSIT_FROZEN notification -
 * DepositService.freezeForAuction no longer calls NotificationService directly.
 *
 * The notification failure is isolated: it must never propagate out of this method. The
 * surrounding transaction has already committed by the time this runs, so an uncaught exception
 * here cannot roll anything back or trigger a retry - it would simply surface to the
 * already-returned caller as a spurious failure of an otherwise successful, already-persisted
 * freeze, which is exactly what this isolation prevents.
 */
@Slf4j
@Component
public class DepositFrozenEventListener {

    private final NotificationService notificationService;

    public DepositFrozenEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDepositFrozen(DepositFrozenEvent event) {
        try {
            notificationService.send(event.userId(), "DEPOSIT_FROZEN",
                    "Депозит заморожен",
                    String.format("Для участия в аукционе #%d заморожено %s сом.",
                            event.auctionId(), event.requiredAmount().toPlainString()));
        } catch (Exception e) {
            log.warn("Failed to send deposit freeze notification to user {}: {}", event.userId(), e.getMessage());
        }
    }
}
