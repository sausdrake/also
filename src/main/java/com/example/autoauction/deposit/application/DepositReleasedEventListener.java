// deposit/application/DepositReleasedEventListener.java
package com.example.autoauction.deposit.application;

import com.example.autoauction.notification.application.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fires only after {@link DepositService#releaseWinnerDeposit}'s transaction has physically
 * committed - a rolled-back release (e.g. a later failure in the same transaction, such as
 * {@code AuctionFinalizer.completeSale}'s own settlement/commitment steps, already proven
 * reachable and already exercised by
 * {@code SaleCommitmentResolutionRollbackIntegrationTest.completeSale_FailureAfterCommitmentClaim_RollsBackEverything})
 * never reaches this listener at all, so it can no longer leak a live notification for a release
 * whose database effects were discarded (G2, mirroring G1's DepositFrozenEventListener).
 *
 * This is now the SOLE dispatch path for the DEPOSIT_RELEASED notification -
 * DepositService.releaseWinnerDeposit no longer calls NotificationService directly.
 *
 * The notification failure is isolated: it must never propagate out of this method. The
 * surrounding transaction has already committed by the time this runs, so an uncaught exception
 * here cannot roll anything back - it would simply surface as a spurious failure of an otherwise
 * successful, already-persisted release, which is exactly what this isolation prevents.
 */
@Slf4j
@Component
public class DepositReleasedEventListener {

    private final NotificationService notificationService;

    public DepositReleasedEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDepositReleased(DepositReleasedEvent event) {
        try {
            notificationService.sendAfterCommit(event.userId(), "DEPOSIT_RELEASED",
                    "Депозит освобождён",
                    String.format("Заморозка %s сом по аукциону #%d снята администратором.",
                            event.amount().toPlainString(), event.auctionId()));
        } catch (Exception e) {
            log.warn("Failed to send deposit release notification to user {}: {}", event.userId(), e.getMessage());
        }
    }
}
