// auction/application/BidService.java (добавлена интеграция с WebSocket)
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.AuctionWebSocketController;
import com.example.autoauction.auction.web.dto.BidResponse;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.notification.application.NotificationService;
import com.example.autoauction.policy.application.PolicyVersionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
public class BidService {

    private final AuctionRepository auctionRepository;
    private final DepositService depositService;
    private final AuctionParticipantRepository participantRepository;
    private final AuctionFinalizer auctionFinalizer;
    private final RateLimitService rateLimitService;
    private final AuctionWebSocketController webSocketController;
    private final NotificationService notificationService;
    private final PolicyVersionService policyVersionService;

    public BidService(AuctionRepository auctionRepository,
                      DepositService depositService,
                      AuctionParticipantRepository participantRepository,
                      AuctionFinalizer auctionFinalizer,
                      RateLimitService rateLimitService,
                      AuctionWebSocketController webSocketController,
                      NotificationService notificationService,
                      PolicyVersionService policyVersionService) {
        this.auctionRepository = auctionRepository;
        this.depositService = depositService;
        this.participantRepository = participantRepository;
        this.auctionFinalizer = auctionFinalizer;
        this.rateLimitService = rateLimitService;
        this.webSocketController = webSocketController;
        this.notificationService = notificationService;
        this.policyVersionService = policyVersionService;
        log.info("BidService initialized with WebSocket and Notification support");
    }

    /**
     * Slice 1b-2: resolves the buyer-facing Buy Now amount that must actually be charged for
     * THIS auction - the raw stored value unchanged for a legacy auction, or the value computed
     * against this auction's own fixed policyVersionId for a policy-bound one. Never
     * PolicyVersionService.getCurrentPublished() - an auction's commission is fixed forever at
     * creation time.
     */
    private BigDecimal resolveBuyerFacingBuyNowPrice(Auction auction) {
        if (auction.getPolicyVersionId() == null) {
            return auction.getBuyNowPrice();
        }
        BigDecimal commissionRate = policyVersionService.getById(auction.getPolicyVersionId()).getCommissionRate();
        return auction.resolveBuyerFacingBuyNowPrice(commissionRate);
    }

    @Transactional
    public BidResponse buyNow(Long auctionId, Long buyerId) {
        log.info("=== BUY NOW ===");
        log.info("Auction ID: {}, Buyer ID: {}", auctionId, buyerId);

        if (auctionId == null) {
            throw new IllegalArgumentException("ID аукциона не может быть null");
        }
        if (buyerId == null) {
            throw new IllegalArgumentException("ID покупателя не может быть null");
        }

        if (rateLimitService != null && !rateLimitService.tryAcquire(buyerId)) {
            log.warn("Rate limit exceeded for user: {}", buyerId);
            throw new RateLimitException("Слишком много запросов. Пожалуйста, подождите.");
        }

        Auction auction = auctionRepository.findByIdWithLock(auctionId)
                .orElseThrow(() -> {
                    log.error("Аукцион не найден: {}", auctionId);
                    return new IllegalArgumentException("Аукцион с ID " + auctionId + " не найден");
                });

        if (auction.getStatus() != AuctionStatus.CREATED) {
            log.warn("Buy Now попытка на аукционе в статусе: {}", auction.getStatus());
            throw new IllegalStateException(
                    String.format("Buy Now доступен только для аукционов в статусе CREATED. Текущий статус: %s",
                            auction.getStatus())
            );
        }

        if (auction.getBuyNowPrice() == null) {
            throw new IllegalStateException("Buy Now не установлен для этого аукциона");
        }

        if (buyerId.equals(auction.getCreatedBy())) {
            log.warn("Пользователь {} попытался купить свой автомобиль", buyerId);
            throw new IllegalArgumentException("Нельзя купить собственный автомобиль");
        }

        BigDecimal requiredDeposit = auction.getRequiredDepositAmount();
        if (requiredDeposit == null) {
            log.error("Auction {} has no requiredDepositAmount configured, rejecting Buy Now", auctionId);
            throw new IllegalStateException(
                    "Для этого аукциона не настроен размер депозита. Обратитесь к администратору.");
        }
        if (!depositService.canParticipateInAuction(buyerId, requiredDeposit)) {
            log.warn("User {} has insufficient deposit for Buy Now. Required: {}", buyerId, requiredDeposit);
            throw new IllegalArgumentException(
                    String.format("Недостаточно средств на депозите для Buy Now. Требуется: %s",
                            requiredDeposit)
            );
        }

        depositService.freezeForAuction(buyerId, auctionId, requiredDeposit);
        log.info("Deposit frozen for user {} on auction {}", buyerId, auctionId);

        participantRepository.registerParticipant(auctionId, buyerId, requiredDeposit);

        BigDecimal buyerFacingBuyNowPrice = resolveBuyerFacingBuyNowPrice(auction);

        auctionFinalizer.finalizeWithBuyNow(auction, buyerId, buyerFacingBuyNowPrice);

        // ✅ Отправляем WebSocket уведомление о Buy Now
        webSocketController.sendBuyNowNotification(auctionId, buyerId, buyerFacingBuyNowPrice);
        webSocketController.sendAuctionUpdate(auction, "BUY_NOW");

        log.info("Аукцион {} завершен по Buy Now. Покупатель: {}", auctionId, buyerId);
        return BidResponse.fromDomain(auction, buyerFacingBuyNowPrice, true, false);
    }

    @Transactional
    @Retryable(
            value = {ObjectOptimisticLockingFailureException.class, OptimisticLockingFailureException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2, random = true)
    )
    public BidResponse placeBid(Long auctionId, BigDecimal amount, Long bidderId) {
        log.info("=== СОЗДАНИЕ СТАВКИ ===");
        log.info("Auction ID: {}, Bidder ID: {}, Amount: {}", auctionId, bidderId, amount);

        if (auctionId == null) {
            throw new NullPointerException("ID аукциона не может быть null");
        }
        if (bidderId == null) {
            throw new NullPointerException("ID участника не может быть null");
        }
        if (amount == null) {
            throw new NullPointerException("Сумма ставки не может быть null");
        }

        if (rateLimitService != null && !rateLimitService.tryAcquire(bidderId)) {
            log.warn("Rate limit exceeded for user: {}", bidderId);
            throw new RateLimitException("Слишком много ставок. Пожалуйста, подождите.");
        }

        Auction auction = auctionRepository.findByIdWithLock(auctionId)
                .orElseThrow(() -> {
                    log.error("Аукцион не найден: {}", auctionId);
                    return new IllegalArgumentException("Аукцион с ID " + auctionId + " не найден");
                });

        log.debug("Текущий статус аукциона: {}, текущая цена: {}, минимальный шаг: {}, версия: {}",
                auction.getStatus(), auction.getCurrentPrice(), auction.getMinBidStep(), auction.getVersion());

        if (auction.getStatus() != AuctionStatus.ACTIVE) {
            log.warn("Попытка ставки на неактивный аукцион. Статус: {}", auction.getStatus());
            throw new IllegalStateException(
                    String.format("Невозможно сделать ставку на аукцион в статусе '%s'. Только активные аукционы принимают ставки",
                            auction.getStatus())
            );
        }

        if (auction.getEndTime().isBefore(OffsetDateTime.now())) {
            throw new IllegalStateException("Время аукциона истекло");
        }

        if (bidderId.equals(auction.getCreatedBy())) {
            log.warn("Попытка сделать ставку на свой аукцион от пользователя {}", bidderId);
            throw new IllegalArgumentException("Нельзя делать ставки на собственный аукцион");
        }

        boolean isExistingParticipant = participantRepository.isParticipant(auctionId, bidderId);
        boolean wasAuctionExtended = false;

        if (!isExistingParticipant) {
            BigDecimal requiredDeposit = auction.getRequiredDepositAmount();
            if (requiredDeposit == null) {
                log.error("Auction {} has no requiredDepositAmount configured, rejecting bid", auctionId);
                throw new IllegalStateException(
                        "Для этого аукциона не настроен размер депозита. Обратитесь к администратору.");
            }

            if (!depositService.canParticipateInAuction(bidderId, requiredDeposit)) {
                log.warn("User {} has insufficient deposit. Required: {}", bidderId, requiredDeposit);
                throw new IllegalArgumentException(
                        String.format("Недостаточно средств на депозите. Требуется: %s",
                                requiredDeposit)
                );
            }

            try {
                depositService.freezeForAuction(bidderId, auctionId, requiredDeposit);
                log.info("Deposit frozen for user {} on auction {}", bidderId, auctionId);
            } catch (Exception e) {
                log.error("Failed to freeze deposit for user {} on auction {}: {}",
                        bidderId, auctionId, e.getMessage());
                throw new IllegalArgumentException("Ошибка при заморозке депозита: " + e.getMessage());
            }

            participantRepository.registerParticipant(auctionId, bidderId, requiredDeposit);
        }

        Long prevLeaderId = auction.getWinnerId();

        boolean auctionExtended = false;
        OffsetDateTime oldEndTime = auction.getEndTime();

        try {
            auction.addBid(amount, bidderId, OffsetDateTime.now());

            if (auction.getEndTime().isAfter(oldEndTime)) {
                auctionExtended = true;
                wasAuctionExtended = true;
                log.info("Аукцион {} продлен! Новое время окончания: {}", auctionId, auction.getEndTime());

                // ✅ Отправляем уведомление о продлении
                webSocketController.sendAuctionExtended(auctionId, auction.getEndTime());
            }

            log.info("Ставка {} успешно принята, новая цена: {}, лидер: {}",
                    amount, auction.getCurrentPrice(), bidderId);

            // ✅ Отправляем уведомление о новой ставке
            webSocketController.sendAuctionUpdate(auction, "BID_PLACED");

            // Уведомление предыдущему лидеру о том, что его ставку перебили
            if (prevLeaderId != null && !prevLeaderId.equals(bidderId)) {
                try {
                    notificationService.send(prevLeaderId, "OUTBID",
                            "Вашу ставку перебили",
                            String.format("В аукционе \"%s\" сделана новая ставка: %s сом. Успейте сделать новую ставку!",
                                    auction.getVehicleInfo(), amount.toPlainString()),
                            auctionId, "AUCTION");
                } catch (Exception e2) {
                    log.warn("Failed to send outbid notification to user {}: {}", prevLeaderId, e2.getMessage());
                }
            }

            // Notify seller about new bid on their vehicle
            Long sellerId = auction.getCreatedBy();
            if (sellerId != null && !sellerId.equals(bidderId)) {
                try {
                    notificationService.send(sellerId, "NEW_BID_ON_VEHICLE",
                            "Новая ставка на ваш автомобиль",
                            String.format("На ваш автомобиль \"%s\" была сделана новая ставка %s сом.",
                                    auction.getVehicleInfo(), amount.toPlainString()),
                            auctionId, "AUCTION");
                } catch (Exception e) {
                    log.warn("Failed to notify seller {} about new bid: {}", sellerId, e.getMessage());
                }
            }

            // Notify bidder that they are now leading
            try {
                notificationService.send(bidderId, "AUCTION_LEADER",
                        "Вы лидер торгов!",
                        String.format("Ваша ставка %s сом является максимальной по лоту \"%s\".",
                                amount.toPlainString(), auction.getVehicleInfo()),
                        auctionId, "AUCTION");
            } catch (Exception e) {
                log.warn("Failed to notify bidder {} about leadership: {}", bidderId, e.getMessage());
            }

        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("Ошибка при создании ставки: {}", e.getMessage());

            if (!isExistingParticipant) {
                try {
                    depositService.unfreezeForAuction(bidderId, auctionId);
                    log.info("Deposit unfrozen for user {} due to failed bid", bidderId);
                } catch (Exception ex) {
                    log.warn("Failed to unfreeze deposit for user {}: {}", bidderId, ex.getMessage());
                }
            }
            throw e;
        }

        Auction saved = auctionRepository.save(auction);
        log.debug("Аукцион сохранен, версия: {}", saved.getVersion());

        log.info("Ставка успешно создана! Итоговый статус аукциона: {}, продление: {}",
                saved.getStatus(), auctionExtended);

        return BidResponse.fromDomain(saved, amount, false, auctionExtended);
    }

    @Transactional(readOnly = true)
    public List<Long> getAuctionParticipants(Long auctionId) {
        return participantRepository.getParticipants(auctionId);
    }
}