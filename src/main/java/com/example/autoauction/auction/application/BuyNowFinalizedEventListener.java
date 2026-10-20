// auction/application/BuyNowFinalizedEventListener.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.web.AuctionWebSocketController;
import com.example.autoauction.notification.application.NotificationService;
import com.example.autoauction.subscription.application.LotSubscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private final LotSubscriptionService lotSubscriptionService;
    private final AuctionSellerResolver auctionSellerResolver;

    public BuyNowFinalizedEventListener(AuctionWebSocketController webSocketController,
                                        LotSubscriptionService lotSubscriptionService,
                                        NotificationService notificationService,
                                        AuctionSellerResolver auctionSellerResolver) {
        this.webSocketController = webSocketController;
        this.lotSubscriptionService = lotSubscriptionService;
        this.notificationService = notificationService;
        this.auctionSellerResolver = auctionSellerResolver;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBuyNowFinalized(BuyNowFinalizedEvent event) {
        Auction auction = event.auction();
        Long auctionId = auction.getId();
        Long winnerId = event.winnerId();
        BigDecimal buyNowAmount = event.buyNowAmount();

        try {
            notificationService.sendAfterCommit(winnerId, "AUCTION_WON",
                    "Вы выиграли аукцион!",
                    String.format("Поздравляем! Вы купили \"%s\" по цене %s сом (Buy Now).",
                            auction.getVehicleInfo(), buyNowAmount.toPlainString()),
                    auctionId, "AUCTION");
        } catch (Exception e) {
            log.warn("Failed to send Buy Now win notification to user {}: {}", winnerId, e.getMessage());
        }

        notifyCreatorAndFollowers(auction, winnerId, buyNowAmount);

        try {
            webSocketController.sendBuyNowNotification(auctionId, winnerId, buyNowAmount);
            webSocketController.sendAuctionUpdate(auction, "BUY_NOW");
        } catch (Exception e) {
            log.warn("Failed to send Buy Now WebSocket notification for auction {}: {}", auctionId, e.getMessage());
        }
    }

    /**
     * Scenario 9.3: everybody involved learns that the lot is gone. As for a normally completed
     * auction, the seller (NOTIF-01) and the auction's creator get AUCTION_COMPLETED; Buy Now happens before the start, so
     * nobody has bid yet - the people following the lot (its subscribers) get AUCTION_ENDED.
     * Each recipient is notified once; one failed notification never stops the others.
     */
    private void notifyCreatorAndFollowers(Auction auction, Long winnerId, BigDecimal buyNowAmount) {
        Long auctionId = auction.getId();
        Set<Long> notified = new HashSet<>();
        notified.add(winnerId);

        Set<Long> completedRecipients = new java.util.LinkedHashSet<>();
        auctionSellerResolver.resolveSellerId(auction).ifPresent(completedRecipients::add);
        if (auction.getCreatedBy() != null) {
            completedRecipients.add(auction.getCreatedBy());
        }
        for (Long creatorId : completedRecipients) {
            if (!notified.add(creatorId)) {
                continue;
            }
            try {
                notificationService.sendAfterCommit(creatorId, "AUCTION_COMPLETED",
                        "Аукцион завершён",
                        String.format("Аукцион \"%s\" завершён покупкой по цене Buy Now (%s сом).",
                                auction.getVehicleInfo(), buyNowAmount.toPlainString()),
                        auctionId, "AUCTION");
            } catch (Exception e) {
                log.warn("Failed to send Buy Now AUCTION_COMPLETED to user {}: {}", creatorId, e.getMessage());
            }
        }

        List<Long> followers;
        try {
            followers = lotSubscriptionService.getSubscriberIds(auctionId);
        } catch (Exception e) {
            log.warn("Failed to load followers of auction {}: {}", auctionId, e.getMessage());
            return;
        }
        for (Long followerId : followers) {
            if (followerId == null || !notified.add(followerId)) {
                continue;
            }
            try {
                notificationService.sendAfterCommit(followerId, "AUCTION_ENDED",
                        "Аукцион завершён",
                        String.format("Автомобиль \"%s\" куплен по цене Buy Now. Торги по этому лоту не состоятся.",
                                auction.getVehicleInfo()),
                        auctionId, "AUCTION");
            } catch (Exception e) {
                log.warn("Failed to send Buy Now AUCTION_ENDED to user {}: {}", followerId, e.getMessage());
            }
        }
    }
}
