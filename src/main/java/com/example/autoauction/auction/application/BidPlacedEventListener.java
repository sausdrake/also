// auction/application/BidPlacedEventListener.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.web.AuctionWebSocketController;
import com.example.autoauction.notification.application.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fires only after {@link BidService#placeBid}'s transaction has physically committed - a
 * retried/rolled-back attempt (e.g. on a genuine optimistic-lock conflict) never reaches this
 * listener at all, so it can no longer leak a live WebSocket push or notification for a bid whose
 * database effects were discarded (MVP-001).
 *
 * Every side effect below is individually isolated: a WebSocket/notification failure here must
 * never propagate out of this method. The surrounding transaction has already committed by the
 * time this runs, so an uncaught exception here cannot roll anything back or trigger a retry - it
 * would simply surface to the already-returned caller as a spurious failure of an otherwise
 * successful, already-persisted bid, which is exactly what this isolation prevents.
 */
@Slf4j
@Component
public class BidPlacedEventListener {

    private final AuctionWebSocketController webSocketController;
    private final NotificationService notificationService;
    private final AuctionSellerResolver auctionSellerResolver;

    public BidPlacedEventListener(AuctionWebSocketController webSocketController,
                                  NotificationService notificationService,
                                  AuctionSellerResolver auctionSellerResolver) {
        this.webSocketController = webSocketController;
        this.notificationService = notificationService;
        this.auctionSellerResolver = auctionSellerResolver;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBidPlaced(BidPlacedEvent event) {
        Auction auction = event.auction();
        Long auctionId = auction.getId();
        Long bidderId = event.bidderId();

        if (event.auctionExtended()) {
            try {
                webSocketController.sendAuctionExtended(auctionId, auction.getEndTime());
            } catch (Exception e) {
                log.warn("Failed to send auction-extended WS update for auction {}: {}", auctionId, e.getMessage());
            }
        }

        try {
            webSocketController.sendAuctionUpdate(auction, "BID_PLACED");
        } catch (Exception e) {
            log.warn("Failed to send bid-placed WS update for auction {}: {}", auctionId, e.getMessage());
        }

        Long prevLeaderId = event.prevLeaderId();
        if (prevLeaderId != null && !prevLeaderId.equals(bidderId)) {
            try {
                notificationService.send(prevLeaderId, "OUTBID",
                        "Вашу ставку перебили",
                        String.format("В аукционе \"%s\" сделана новая ставка: %s сом. Успейте сделать новую ставку!",
                                auction.getVehicleInfo(), event.bidAmount().toPlainString()),
                        auctionId, "AUCTION");
            } catch (Exception e) {
                log.warn("Failed to send outbid notification to user {}: {}", prevLeaderId, e.getMessage());
            }
        }

        // NOTIF-01: "a new bid on your vehicle" goes to the vehicle owner, not to the administrator
        // who created the auction (unknown seller of a historical auction: the creator, as before).
        Long sellerId = auctionSellerResolver.resolveSellerId(auction).orElse(auction.getCreatedBy());
        if (sellerId != null && !sellerId.equals(bidderId)) {
            try {
                notificationService.send(sellerId, "NEW_BID_ON_VEHICLE",
                        "Новая ставка на ваш автомобиль",
                        String.format("На ваш автомобиль \"%s\" была сделана новая ставка %s сом.",
                                auction.getVehicleInfo(), event.bidAmount().toPlainString()),
                        auctionId, "AUCTION");
            } catch (Exception e) {
                log.warn("Failed to notify seller {} about new bid: {}", sellerId, e.getMessage());
            }
        }

        try {
            notificationService.send(bidderId, "AUCTION_LEADER",
                    "Вы лидер торгов!",
                    String.format("Ваша ставка %s сом является максимальной по лоту \"%s\".",
                            event.bidAmount().toPlainString(), auction.getVehicleInfo()),
                    auctionId, "AUCTION");
        } catch (Exception e) {
            log.warn("Failed to notify bidder {} about leadership: {}", bidderId, e.getMessage());
        }
    }
}
