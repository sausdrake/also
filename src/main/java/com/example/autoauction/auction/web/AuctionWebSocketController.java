// auction/web/AuctionWebSocketController.java
package com.example.autoauction.auction.web;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.web.dto.AuctionWebSocketMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

@Slf4j
@Controller
@RequiredArgsConstructor
public class AuctionWebSocketController {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Отправить обновление аукциона всем подписчикам
     */
    public void sendAuctionUpdate(Auction auction, String messageType) {
        if (auction == null) {
            log.warn("Cannot send WebSocket update: auction is null");
            return;
        }

        AuctionWebSocketMessage message = buildMessage(auction, messageType);

        log.debug("Sending WebSocket update for auction {}: type={}, price={}",
                auction.getId(), messageType, auction.getCurrentPrice());

        messagingTemplate.convertAndSend(
                "/topic/auction/" + auction.getId(),
                message
        );
    }

    /**
     * Отправить обновление конкретному пользователю (победителю)
     */
    public void sendPersonalUpdate(Long userId, Auction auction, String messageType) {
        if (userId == null || auction == null) {
            log.warn("Cannot send personal WebSocket update: userId={}, auction={}", userId, auction);
            return;
        }

        AuctionWebSocketMessage message = buildMessage(auction, messageType);

        messagingTemplate.convertAndSendToUser(
                userId.toString(),
                "/queue/auction-updates",
                message
        );

        log.debug("Sent personal update to user {} for auction {}", userId, auction.getId());
    }

    /**
     * Отправить уведомление о новом лидере
     */
    public void sendNewLeaderUpdate(Long auctionId, Long newLeaderId, BigDecimal newPrice, String leaderName) {
        AuctionWebSocketMessage message = AuctionWebSocketMessage.builder()
                .auctionId(auctionId)
                .currentPrice(newPrice)
                .winnerId(newLeaderId)
                .winnerName(leaderName)
                .messageType("NEW_LEADER")
                .timestamp(OffsetDateTime.now())
                .build();

        messagingTemplate.convertAndSend(
                "/topic/auction/" + auctionId,
                message
        );
    }

    /**
     * Отправить уведомление о продлении аукциона
     */
    public void sendAuctionExtended(Long auctionId, OffsetDateTime newEndTime) {
        AuctionWebSocketMessage message = AuctionWebSocketMessage.builder()
                .auctionId(auctionId)
                .endTime(newEndTime)
                .timeLeftSeconds(calculateTimeLeft(newEndTime))
                .messageType("AUCTION_EXTENDED")
                .timestamp(OffsetDateTime.now())
                .build();

        messagingTemplate.convertAndSend(
                "/topic/auction/" + auctionId,
                message
        );

        log.info("Auction {} extended notification sent. New end time: {}", auctionId, newEndTime);
    }

    /**
     * Отправить уведомление о завершении аукциона
     */
    public void sendAuctionEnded(Long auctionId, Long winnerId, BigDecimal winningBid) {
        AuctionWebSocketMessage message = AuctionWebSocketMessage.builder()
                .auctionId(auctionId)
                .winnerId(winnerId)
                .winningBid(winningBid)
                .messageType("AUCTION_ENDED")
                .timestamp(OffsetDateTime.now())
                .build();

        messagingTemplate.convertAndSend(
                "/topic/auction/" + auctionId,
                message
        );

        log.info("Auction {} ended notification sent. Winner: {}, Winning bid: {}",
                auctionId, winnerId, winningBid);
    }

    /**
     * Отправить уведомление о покупке по Buy Now
     */
    public void sendBuyNowNotification(Long auctionId, Long buyerId, BigDecimal buyNowPrice) {
        AuctionWebSocketMessage message = AuctionWebSocketMessage.builder()
                .auctionId(auctionId)
                .winnerId(buyerId)
                .winningBid(buyNowPrice)
                .messageType("BUY_NOW")
                .timestamp(OffsetDateTime.now())
                .build();

        messagingTemplate.convertAndSend(
                "/topic/auction/" + auctionId,
                message
        );

        log.info("Buy Now notification sent for auction {} to buyer {}", auctionId, buyerId);
    }

    /**
     * Построить сообщение из доменного объекта
     */
    private AuctionWebSocketMessage buildMessage(Auction auction, String messageType) {
        return AuctionWebSocketMessage.builder()
                .auctionId(auction.getId())
                .vehicleId(auction.getVehicleId())
                .vehicleInfo(auction.getVehicleInfo())
                .currentPrice(auction.getCurrentPrice())
                .winningBid(auction.getWinningBid())
                .winnerId(auction.getWinnerId())
                .totalBids(auction.getTotalBids())
                .status(auction.getStatus())
                .endTime(auction.getEndTime())
                .timeLeftSeconds(calculateTimeLeft(auction.getEndTime()))
                .messageType(messageType)
                .timestamp(OffsetDateTime.now())
                .build();
    }

    /**
     * Рассчитать количество секунд до окончания аукциона
     */
    private Long calculateTimeLeft(OffsetDateTime endTime) {
        if (endTime == null) {
            return 0L;
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (endTime.isBefore(now)) {
            return 0L;
        }

        return ChronoUnit.SECONDS.between(now, endTime);
    }
}