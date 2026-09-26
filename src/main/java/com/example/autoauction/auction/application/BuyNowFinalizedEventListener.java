// auction/application/BuyNowFinalizedEventListener.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.web.AuctionWebSocketController;
import com.example.autoauction.notification.application.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;

/**
 * Fires only after {@link AuctionFinalizer#finalizeWithBuyNow}'s transaction has physically
 * committed - a rolled-back Buy Now never reaches this listener at all, so it can no longer leak
 * a live WebSocket push or notification for a sale whose database effects were discarded
 * (MVP-002, mirroring MVP-001's BidPlacedEventListener).
 *
 * This is now the SOLE dispatch path for Buy Now's WebSocket update and win notification -
 * finalizeWithBuyNow no longer calls these directly, and BidService.buyNow()'s previously
 * redundant, unguarded duplicate calls have been removed (see .auto-dev/mvp-002-plan.md §1/§8).
 *
 * Every side effect below is individually isolated: a WebSocket/notification failure here must
 * never propagate out of this method. The surrounding transaction has already committed by the
 * time this runs, so an uncaught exception here cannot roll anything back or trigger a retry - it
 * would simply surface to the already-returned caller as a spurious failure of an otherwise
 * successful, already-persisted Buy Now sale, which is exactly what this isolation prevents.
 */
@Slf4j
@Component
public class BuyNowFinalizedEventListener {

    private final AuctionWebSocketController webSocketController;
    private final NotificationService notificationService;

    public BuyNowFinalizedEventListener(AuctionWebSocketController webSocketController,
                                        NotificationService notificationService) {
        this.webSocketController = webSocketController;
        this.notificationService = notificationService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBuyNowFinalized(BuyNowFinalizedEvent event) {
        Auction auction = event.auction();
        Long auctionId = auction.getId();
        Long winnerId = event.winnerId();
        BigDecimal buyNowAmount = event.buyNowAmount();

        try {
            notificationService.send(winnerId, "AUCTION_WON",
                    "Вы выиграли аукцион!",
                    String.format("Поздравляем! Вы купили \"%s\" по цене %s сом (Buy Now).",
                            auction.getVehicleInfo(), buyNowAmount.toPlainString()),
                    auctionId, "AUCTION");
        } catch (Exception e) {
            log.warn("Failed to send Buy Now win notification to user {}: {}", winnerId, e.getMessage());
        }

        try {
            webSocketController.sendBuyNowNotification(auctionId, winnerId, buyNowAmount);
            webSocketController.sendAuctionUpdate(auction, "BUY_NOW");
        } catch (Exception e) {
            log.warn("Failed to send Buy Now WebSocket notification for auction {}: {}", auctionId, e.getMessage());
        }
    }
}
