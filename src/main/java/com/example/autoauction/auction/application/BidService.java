// auction/application/BidService.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.dto.BidResponse;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
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

    public BidService(AuctionRepository auctionRepository,
                      DepositService depositService,
                      AuctionParticipantRepository participantRepository,
                      AuctionFinalizer auctionFinalizer) {
        this.auctionRepository = auctionRepository;
        this.depositService = depositService;
        this.participantRepository = participantRepository;
        this.auctionFinalizer = auctionFinalizer;
        log.info("BidService initialized with database-backed participant tracking");
    }

    /**
     * Buy Now - покупка автомобиля по фиксированной цене до начала аукциона
     */
    @Transactional
    public BidResponse buyNow(Long auctionId, Long buyerId) {
        log.info("=== BUY NOW ===");
        log.info("Auction ID: {}, Buyer ID: {}", auctionId, buyerId);

        Auction auction = auctionRepository.findByIdWithLock(auctionId)
                .orElseThrow(() -> {
                    log.error("Аукцион не найден: {}", auctionId);
                    return new IllegalArgumentException("Аукцион с ID " + auctionId + " не найден");
                });

        // Buy Now доступен только для CREATED статуса
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

        // Проверка депозита (1% от стартовой цены)
        BigDecimal requiredDeposit = Deposit.calculateRequiredDeposit(auction.getStartingPrice());
        if (!depositService.canParticipateInAuction(buyerId, auction.getStartingPrice())) {
            log.warn("User {} has insufficient deposit for Buy Now. Required: {}", buyerId, requiredDeposit);
            throw new IllegalArgumentException(
                    String.format("Недостаточно средств на депозите для Buy Now. Требуется: %s (1%% от стартовой цены %s)",
                            requiredDeposit, auction.getStartingPrice())
            );
        }

        // Заморозка депозита
        depositService.freezeForAuction(buyerId, auctionId, auction.getStartingPrice());
        log.info("Deposit frozen for user {} on auction {}", buyerId, auctionId);

        // Регистрация участника
        participantRepository.registerParticipant(auctionId, buyerId, requiredDeposit);

        // Завершение аукциона по Buy Now
        auctionFinalizer.finalizeWithBuyNow(auction, buyerId, auction.getBuyNowPrice());

        log.info("Аукцион {} завершен по Buy Now. Покупатель: {}", auctionId, buyerId);
        return BidResponse.fromDomain(auction, auction.getBuyNowPrice(), true, false);
    }

    /**
     * Обычная ставка на активном аукционе
     */
    @Transactional
    @Retryable(
            value = {ObjectOptimisticLockingFailureException.class, OptimisticLockingFailureException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2, random = true)
    )
    public BidResponse placeBid(Long auctionId, BigDecimal amount, Long bidderId) {
        log.info("=== СОЗДАНИЕ СТАВКИ ===");
        log.info("Auction ID: {}, Bidder ID: {}, Amount: {}", auctionId, bidderId, amount);

        if (amount == null) {
            throw new NullPointerException("Сумма ставки не может быть null");
        }

        Auction auction = auctionRepository.findByIdWithLock(auctionId)
                .orElseThrow(() -> {
                    log.error("Аукцион не найден: {}", auctionId);
                    return new IllegalArgumentException("Аукцион с ID " + auctionId + " не найден");
                });

        log.debug("Текущий статус аукциона: {}, текущая цена: {}, минимальный шаг: {}, версия: {}",
                auction.getStatus(), auction.getCurrentPrice(), auction.getMinBidStep(), auction.getVersion());

        // Только активные аукционы принимают ставки
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

        if (!isExistingParticipant) {
            BigDecimal requiredDeposit = Deposit.calculateRequiredDeposit(auction.getStartingPrice());

            if (!depositService.canParticipateInAuction(bidderId, auction.getStartingPrice())) {
                log.warn("User {} has insufficient deposit. Required: {}", bidderId, requiredDeposit);
                throw new IllegalArgumentException(
                        String.format("Недостаточно средств на депозите. Требуется: %s (1%% от стартовой цены %s)",
                                requiredDeposit, auction.getStartingPrice())
                );
            }

            try {
                depositService.freezeForAuction(bidderId, auctionId, auction.getStartingPrice());
                log.info("Deposit frozen for user {} on auction {}", bidderId, auctionId);
            } catch (Exception e) {
                log.error("Failed to freeze deposit for user {} on auction {}: {}",
                        bidderId, auctionId, e.getMessage());
                throw new IllegalArgumentException("Ошибка при заморозке депозита: " + e.getMessage());
            }

            participantRepository.registerParticipant(auctionId, bidderId, requiredDeposit);
        }

        boolean auctionExtended = false;
        OffsetDateTime oldEndTime = auction.getEndTime();

        try {
            auction.addBid(amount, bidderId, OffsetDateTime.now());

            if (auction.getEndTime().isAfter(oldEndTime)) {
                auctionExtended = true;
                log.info("Аукцион {} продлен! Новое время окончания: {}", auctionId, auction.getEndTime());
            }

            log.info("Ставка {} успешно принята, новая цена: {}, лидер: {}",
                    amount, auction.getCurrentPrice(), bidderId);

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