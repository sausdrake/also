// auction/application/AuctionFinalizedEventListener.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.web.AuctionWebSocketController;
import com.example.autoauction.notification.application.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.List;

/**
 * Fires only after {@link AuctionFinalizer#finalizeAuction}'s transaction has physically
 * committed - a rolled-back finalization (e.g. a genuine optimistic-lock conflict on the
 * auction's own status save, discovered by the already-proven scheduler-retry scenario in
 * {@code AuctionFinalizationSchedulerTransactionIsolationIntegrationTest}) never reaches this
 * listener at all, so it can no longer leak a live WebSocket push or notification for a
 * finalization whose database effects were discarded (MVP-003, mirroring MVP-001's
 * BidPlacedEventListener and MVP-002's BuyNowFinalizedEventListener).
 *
 * This is now the SOLE dispatch path for finalizeAuction's WebSocket updates and notifications -
 * finalizeAuction no longer calls these directly.
 *
 * Every side effect below is individually isolated: a WebSocket/notification failure here must
 * never propagate out of this method, and one failing must not prevent the other, independent
 * side effects from being attempted - matching BidPlacedEventListener's fine-grained,
 * one-try-catch-per-call discipline (not BuyNowFinalizedEventListener's paired grouping), since
 * that is how finalizeAuction's own pre-existing code already isolated each of these calls. The
 * surrounding transaction has already committed by the time this runs, so an uncaught exception
 * here cannot roll anything back or trigger a retry - it would simply surface to the
 * already-returned caller as a spurious failure of an otherwise successful, already-persisted
 * finalization, which is exactly what this isolation prevents.
 */
@Slf4j
@Component
public class AuctionFinalizedEventListener {

    private final AuctionWebSocketController webSocketController;
    private final NotificationService notificationService;

    public AuctionFinalizedEventListener(AuctionWebSocketController webSocketController,
                                         NotificationService notificationService) {
        this.webSocketController = webSocketController;
        this.notificationService = notificationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAuctionFinalized(AuctionFinalizedEvent event) {
        Auction auction = event.auction();
        Long auctionId = auction.getId();
        Long winnerId = event.winnerId();
        BigDecimal winningBid = event.winningBid();

        if (winnerId != null) {
            try {
                webSocketController.sendPersonalUpdate(winnerId, auction, "YOU_WON");
            } catch (Exception e) {
                log.warn("Failed to send personal WebSocket notification to user {}: {}", winnerId, e.getMessage());
            }
            try {
                notificationService.send(winnerId, "AUCTION_WON",
                        "Вы выиграли аукцион!",
                        String.format("Поздравляем! Вы выиграли аукцион \"%s\" с ценой %s сом.",
                                auction.getVehicleInfo(), winningBid.toPlainString()),
                        auctionId, "AUCTION");
            } catch (Exception e) {
                log.warn("Failed to send win notification to user {}: {}", winnerId, e.getMessage());
            }

            List<Long> participantIds = event.participantIds();
            for (Long participantId : participantIds) {
                if (!participantId.equals(winnerId)) {
                    try {
                        notificationService.send(participantId, "AUCTION_LOST",
                                "Вы не выиграли аукцион",
                                String.format("Аукцион \"%s\" завершён. Победителем стал другой участник. Ваши средства разблокированы.",
                                        auction.getVehicleInfo()),
                                auctionId, "AUCTION");
                    } catch (Exception e) {
                        log.warn("Failed to send AUCTION_LOST to user {}: {}", participantId, e.getMessage());
                    }
                }
            }

            Long sellerId = auction.getCreatedBy();
            if (sellerId != null) {
                try {
                    notificationService.send(sellerId, "AUCTION_COMPLETED",
                            "Аукцион завершён",
                            String.format("Аукцион \"%s\" завершён. Победитель определён.", auction.getVehicleInfo()),
                            auctionId, "AUCTION");
                } catch (Exception e) {
                    log.warn("Failed to send AUCTION_COMPLETED to seller {}: {}", sellerId, e.getMessage());
                }
            }

            try {
                webSocketController.sendAuctionEnded(auctionId, winnerId, winningBid);
            } catch (Exception e) {
                log.warn("Failed to send WebSocket notification for auction {}: {}", auctionId, e.getMessage());
            }
        } else {
            try {
                webSocketController.sendAuctionEnded(auctionId, null, null);
            } catch (Exception e) {
                log.warn("Failed to send WebSocket notification for auction {}: {}", auctionId, e.getMessage());
            }
        }

        try {
            webSocketController.sendAuctionUpdate(auction, "AUCTION_FINALIZED");
        } catch (Exception e) {
            log.warn("Failed to send final WebSocket update for auction {}: {}", auctionId, e.getMessage());
        }
    }
}
