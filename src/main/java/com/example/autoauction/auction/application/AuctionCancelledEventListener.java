// auction/application/AuctionCancelledEventListener.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.web.AuctionWebSocketController;
import com.example.autoauction.notification.application.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fires only after {@link AuctionFinalizer#cancelAuction}'s transaction has physically
 * committed - a rolled-back cancellation (e.g. a genuine optimistic-lock conflict on the
 * auction's own status save, discovered by the already-proven scheduler-vs-admin race scenario
 * in {@code AuctionCompletionSchedulerTransactionIsolationIntegrationTest}) never reaches this
 * listener at all, so it can no longer leak a live WebSocket push or notification for a
 * cancellation whose database effects were discarded (MVP-004, mirroring MVP-001's
 * BidPlacedEventListener, MVP-002's BuyNowFinalizedEventListener, and MVP-003's
 * AuctionFinalizedEventListener).
 *
 * This is now the SOLE dispatch path for cancelAuction's WebSocket update and notifications -
 * cancelAuction no longer calls these directly.
 *
 * Every side effect below is individually isolated: a WebSocket/notification failure here must
 * never propagate out of this method, and one failing must not prevent the other, independent
 * side effects from being attempted (including each participant's own notification, exactly
 * mirroring the per-iteration try/catch discipline the original inline code already used). The
 * surrounding transaction has already committed by the time this runs, so an uncaught exception
 * here cannot roll anything back or trigger a retry - it would simply surface to the
 * already-returned caller as a spurious failure of an otherwise successful, already-persisted
 * cancellation, which is exactly what this isolation prevents.
 */
@Slf4j
@Component
public class AuctionCancelledEventListener {

    private final AuctionWebSocketController webSocketController;
    private final NotificationService notificationService;

    public AuctionCancelledEventListener(AuctionWebSocketController webSocketController,
                                         NotificationService notificationService) {
        this.webSocketController = webSocketController;
        this.notificationService = notificationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAuctionCancelled(AuctionCancelledEvent event) {
        Auction auction = event.auction();
        Long auctionId = auction.getId();

        try {
            webSocketController.sendAuctionEnded(auctionId, null, null);
        } catch (Exception e) {
            log.warn("Failed to send WebSocket notification for cancelled auction {}: {}", auctionId, e.getMessage());
        }

        for (Long participantId : event.participantIds()) {
            try {
                notificationService.send(participantId, "AUCTION_CANCELLED",
                        "Аукцион отменён",
                        String.format("Аукцион \"%s\" был отменён администратором. Ваши средства разблокированы.",
                                auction.getVehicleInfo()),
                        auctionId, "AUCTION");
            } catch (Exception e) {
                log.warn("Failed to send AUCTION_CANCELLED to user {}: {}", participantId, e.getMessage());
            }
        }
    }
}
