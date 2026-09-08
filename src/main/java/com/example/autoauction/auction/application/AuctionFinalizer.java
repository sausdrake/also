// auction/application/AuctionFinalizer.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.AuctionWebSocketController;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.notification.application.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
public class AuctionFinalizer {

    private final AuctionRepository auctionRepository;
    private final DepositService depositService;
    private final AuctionParticipantRepository participantRepository;
    private final AuctionWebSocketController webSocketController;
    private final NotificationService notificationService;
    private final Clock clock;

    public AuctionFinalizer(AuctionRepository auctionRepository,
                            DepositService depositService,
                            AuctionParticipantRepository participantRepository,
                            AuctionWebSocketController webSocketController,
                            NotificationService notificationService,
                            Clock clock) {
        this.auctionRepository = auctionRepository;
        this.depositService = depositService;
        this.participantRepository = participantRepository;
        this.webSocketController = webSocketController;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    @Transactional
    public boolean finalizeAuction(Auction auction) {
        if (auction.isFinished()) {
            log.debug("Auction {} is already finished with status {}", auction.getId(), auction.getStatus());
            return false;
        }

        log.info("Finalizing auction {} (vehicle {})", auction.getId(), auction.getVehicleId());

        AuctionStatus finalStatus;
        Long winnerId = null;
        BigDecimal winningBid = null;
        List<Long> participants = participantRepository.getParticipants(auction.getId());

        if (auction.getTotalBids() == null || auction.getTotalBids() == 0) {
            finalStatus = AuctionStatus.EXPIRED; // no-bids branch, unaffected by Slice 1a
            log.info("Auction {} expired with no bids", auction.getId());

            for (Long participantId : participants) {
                unfreezeDepositSafely(participantId, auction.getId());
            }

            // ✅ Отправляем уведомление об истечении
            try {
                webSocketController.sendAuctionEnded(auction.getId(), null, null);
            } catch (Exception e) {
                log.warn("Failed to send WebSocket notification for auction {}: {}", auction.getId(), e.getMessage());
            }

        } else {
            boolean reserveMet = auction.getReservePrice() == null ||
                    auction.getCurrentPrice().compareTo(auction.getReservePrice()) >= 0;

            if (reserveMet) {
                // Slice 1a: bidding concluding with a winner no longer means SOLD - it means the
                // sale is now awaiting the confirmed-sale workflow (payment + handover + admin
                // confirmation), which is outside this slice's scope.
                finalStatus = AuctionStatus.WAITING_FOR_PAYMENT;
                winnerId = auction.getWinnerId();
                winningBid = auction.getWinningBid();

                log.info("Auction {} finished bidding with a winner, awaiting payment confirmation. Winner: {}, Winning bid: {}",
                        auction.getId(), winnerId, winningBid);

                if (winnerId != null) {
                    markWinnerSafely(winnerId, auction.getId());
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
                                auction.getId(), "AUCTION");
                    } catch (Exception e) {
                        log.warn("Failed to send win notification to user {}: {}", winnerId, e.getMessage());
                    }
                }

                for (Long participantId : participants) {
                    if (!participantId.equals(winnerId)) {
                        unfreezeDepositSafely(participantId, auction.getId());
                    }
                }

                // Notify each loser
                for (Long participantId : participants) {
                    if (!participantId.equals(winnerId)) {
                        try {
                            notificationService.send(participantId, "AUCTION_LOST",
                                    "Вы не выиграли аукцион",
                                    String.format("Аукцион \"%s\" завершён. Победителем стал другой участник. Ваши средства разблокированы.",
                                            auction.getVehicleInfo()),
                                    auction.getId(), "AUCTION");
                        } catch (Exception e) {
                            log.warn("Failed to send AUCTION_LOST to user {}: {}", participantId, e.getMessage());
                        }
                    }
                }
                // Notify seller about auction completion
                Long sellerId = auction.getCreatedBy();
                if (sellerId != null) {
                    try {
                        notificationService.send(sellerId, "AUCTION_COMPLETED",
                                "Аукцион завершён",
                                String.format("Аукцион \"%s\" завершён. Победитель определён.", auction.getVehicleInfo()),
                                auction.getId(), "AUCTION");
                    } catch (Exception e) {
                        log.warn("Failed to send AUCTION_COMPLETED to seller {}: {}", sellerId, e.getMessage());
                    }
                }

                // ✅ Отправляем уведомление о завершении
                try {
                    webSocketController.sendAuctionEnded(auction.getId(), winnerId, winningBid);
                } catch (Exception e) {
                    log.warn("Failed to send WebSocket notification for auction {}: {}", auction.getId(), e.getMessage());
                }

            } else {
                finalStatus = AuctionStatus.EXPIRED;
                log.info("Auction {} expired - reserve price not met", auction.getId());

                for (Long participantId : participants) {
                    unfreezeDepositSafely(participantId, auction.getId());
                }

                // ✅ Отправляем уведомление об истечении
                try {
                    webSocketController.sendAuctionEnded(auction.getId(), null, null);
                } catch (Exception e) {
                    log.warn("Failed to send WebSocket notification for auction {}: {}", auction.getId(), e.getMessage());
                }
            }
        }

        auction.setStatus(finalStatus);
        auction.setWinnerId(winnerId);
        auction.setWinningBid(winningBid);
        auction.setUpdatedAt(now());

        auctionRepository.save(auction);
        participantRepository.clearParticipants(auction.getId());

        // ✅ Отправляем финальное обновление
        try {
            webSocketController.sendAuctionUpdate(auction, "AUCTION_FINALIZED");
        } catch (Exception e) {
            log.warn("Failed to send final WebSocket update for auction {}: {}", auction.getId(), e.getMessage());
        }

        log.info("Auction {} finalized with status: {}", auction.getId(), finalStatus);
        return true;
    }

    @Transactional
    public boolean cancelAuction(Auction auction) {
        if (!auction.isCancellable()) {
            log.debug("Auction {} cannot be cancelled from status {}, skipping cancellation", auction.getId(), auction.getStatus());
            return false;
        }

        log.info("Cancelling auction {} (vehicle {})", auction.getId(), auction.getVehicleId());

        List<Long> participants = participantRepository.getParticipants(auction.getId());
        for (Long participantId : participants) {
            unfreezeDepositSafely(participantId, auction.getId());
        }

        auction.cancel();
        auctionRepository.save(auction);
        participantRepository.clearParticipants(auction.getId());

        try {
            webSocketController.sendAuctionEnded(auction.getId(), null, null);
        } catch (Exception e) {
            log.warn("Failed to send WebSocket notification for cancelled auction {}: {}", auction.getId(), e.getMessage());
        }

        for (Long participantId : participants) {
            try {
                notificationService.send(participantId, "AUCTION_CANCELLED",
                        "Аукцион отменён",
                        String.format("Аукцион \"%s\" был отменён администратором. Ваши средства разблокированы.",
                                auction.getVehicleInfo()),
                        auction.getId(), "AUCTION");
            } catch (Exception e) {
                log.warn("Failed to send AUCTION_CANCELLED to user {}: {}", participantId, e.getMessage());
            }
        }

        log.info("Auction {} cancelled, {} participant(s) unfrozen", auction.getId(), participants.size());
        return true;
    }

    @Transactional
    public boolean finalizeAuctionById(Long auctionId) {
        Auction auction = auctionRepository.findByIdWithLock(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Auction not found: " + auctionId));

        return finalizeAuction(auction);
    }

    @Transactional
    public void finalizeWithBuyNow(Auction auction, Long winnerId, BigDecimal buyNowAmount) {
        if (auction.isFinished()) {
            log.warn("Auction {} is already finished with status {}, skipping Buy Now finalization",
                    auction.getId(), auction.getStatus());
            return;
        }

        log.info("Finalizing auction {} with Buy Now by user {}", auction.getId(), winnerId);

        // Slice 1a: Buy Now no longer produces SOLD directly - it produces the same
        // payment-pending state as a normal auction win.
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        auction.setWinnerId(winnerId);
        auction.setWinningBid(buyNowAmount);
        auction.setCurrentPrice(buyNowAmount);
        auction.setUpdatedAt(now());

        auctionRepository.save(auction);
        markWinnerSafely(winnerId, auction.getId());

        List<Long> participants = participantRepository.getParticipants(auction.getId());
        for (Long participantId : participants) {
            if (!participantId.equals(winnerId)) {
                unfreezeDepositSafely(participantId, auction.getId());
            }
        }

        participantRepository.clearParticipants(auction.getId());

        // Уведомление победителю о Buy Now
        try {
            notificationService.send(winnerId, "AUCTION_WON",
                    "Вы выиграли аукцион!",
                    String.format("Поздравляем! Вы купили \"%s\" по цене %s сом (Buy Now).",
                            auction.getVehicleInfo(), buyNowAmount.toPlainString()),
                    auction.getId(), "AUCTION");
        } catch (Exception e) {
            log.warn("Failed to send Buy Now win notification to user {}: {}", winnerId, e.getMessage());
        }

        // ✅ Отправляем уведомление о Buy Now
        try {
            webSocketController.sendBuyNowNotification(auction.getId(), winnerId, buyNowAmount);
            webSocketController.sendAuctionUpdate(auction, "BUY_NOW");
        } catch (Exception e) {
            log.warn("Failed to send Buy Now WebSocket notification for auction {}: {}", auction.getId(), e.getMessage());
        }

        log.info("Auction {} completed via Buy Now. Winner: {}", auction.getId(), winnerId);
    }

    private void unfreezeDepositSafely(Long userId, Long auctionId) {
        try {
            depositService.unfreezeForAuction(userId, auctionId);
            log.debug("Deposit unfrozen for user {} on auction {}", userId, auctionId);
        } catch (Exception e) {
            log.error("Failed to unfreeze deposit for user {} on auction {}: {}", userId, auctionId, e.getMessage());
        }
    }

    private void markWinnerSafely(Long userId, Long auctionId) {
        try {
            depositService.markAsWinner(userId, auctionId);
            log.debug("User {} marked as winner of auction {}", userId, auctionId);
        } catch (Exception e) {
            log.error("CRITICAL: Failed to mark user {} as winner of auction {}: {}",
                    userId, auctionId, e.getMessage(), e);
        }
    }
}