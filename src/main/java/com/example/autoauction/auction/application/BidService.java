// Файл: auction/application/BidService.java

package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.dto.BidResponse;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class BidService {

    private final AuctionRepository auctionRepository;
    private final DepositService depositService;

    // Кэш для отслеживания участников аукционов
    private final ConcurrentHashMap<Long, ConcurrentHashMap<Long, Boolean>> auctionBidders = new ConcurrentHashMap<>();

    public BidService(AuctionRepository auctionRepository, DepositService depositService) {
        this.auctionRepository = auctionRepository;
        this.depositService = depositService;
        log.info("BidService initialized with deposit service integration");
    }

    @Transactional
    public BidResponse placeBid(Long auctionId, BigDecimal amount, Long bidderId) {
        log.info("=== СОЗДАНИЕ СТАВКИ ===");
        log.info("Auction ID: {}, Bidder ID: {}, Amount: {}", auctionId, bidderId, amount);

        // Загружаем аукцион с пессимистичной блокировкой
        Auction auction = auctionRepository.findByIdWithLock(auctionId)
                .orElseThrow(() -> {
                    log.error("Аукцион не найден: {}", auctionId);
                    return new IllegalArgumentException("Аукцион с ID " + auctionId + " не найден");
                });

        log.debug("Текущий статус аукциона: {}, текущая цена: {}, минимальный шаг: {}, время окончания: {}",
                auction.getStatus(), auction.getCurrentPrice(), auction.getMinBidStep(), auction.getEndTime());
        if (amount == null) {
            throw new NullPointerException("Сумма ставки не может быть null");
        }

        log.info("Auction ID: {}, Bidder ID: {}, Amount: {}", auctionId, bidderId, amount);
        // ПРОВЕРКА СТАТУСА АУКЦИОНА - ПЕРЕМЕСТИТЬ СЮДА (ДО ПРОВЕРКИ ДЕПОЗИТА)
        if (auction.getStatus() != AuctionStatus.ACTIVE) {
            log.warn("Попытка ставки на неактивный аукцион. Статус: {}", auction.getStatus());
            throw new IllegalStateException(
                    String.format("Невозможно сделать ставку на аукцион в статусе '%s'. Только активные аукционы принимают ставки",
                            auction.getStatus())
            );
        }

        // Проверяем, не истекло ли время
        if (auction.getEndTime().isBefore(OffsetDateTime.now())) {
            throw new IllegalStateException("Время аукциона истекло");
        }

        // Проверяем, не является ли участник создателем аукциона
        if (bidderId.equals(auction.getCreatedBy())) {
            log.warn("Попытка сделать ставку на свой аукцион от пользователя {}", bidderId);
            throw new IllegalArgumentException("Нельзя делать ставки на собственный аукцион");
        }

        // Проверяем, есть ли уже ставки у пользователя на этот аукцион
        boolean hasExistingBids = checkIfUserHasBids(auctionId, bidderId);

        // Если это первая ставка пользователя на этот аукцион - проверяем и замораживаем депозит
        if (!hasExistingBids) {
            BigDecimal requiredDeposit = Deposit.calculateRequiredDeposit(auction.getStartingPrice());

            if (!depositService.canParticipateInAuction(bidderId, auction.getStartingPrice())) {
                log.warn("User {} has insufficient deposit. Required: {}", bidderId, requiredDeposit);
                throw new IllegalArgumentException(
                        String.format("Недостаточно средств на депозите. Требуется: %s (1%% от стартовой цены %s)",
                                requiredDeposit, auction.getStartingPrice())
                );
            }

            // Замораживаем депозит
            try {
                depositService.freezeForAuction(bidderId, auctionId, auction.getStartingPrice());
                log.info("Deposit frozen for user {} on auction {}", bidderId, auctionId);
            } catch (Exception e) {
                log.error("Failed to freeze deposit for user {} on auction {}: {}",
                        bidderId, auctionId, e.getMessage());
                throw new IllegalArgumentException("Ошибка при заморозке депозита: " + e.getMessage());
            }
        }

        boolean buyNowUsed = false;
        boolean auctionExtended = false;
        OffsetDateTime oldEndTime = auction.getEndTime();

        try {
            // Вызываем бизнес-логику добавления ставки с текущим временем
            auction.addBid(amount, bidderId, OffsetDateTime.now());

            // Проверяем, было ли продление
            if (auction.getEndTime().isAfter(oldEndTime)) {
                auctionExtended = true;
                log.info("Аукцион {} продлен! Новое время окончания: {}", auctionId, auction.getEndTime());
            }

            // Проверяем, была ли использована цена мгновенной покупки
            if (auction.getBuyNowPrice() != null && amount.compareTo(auction.getBuyNowPrice()) >= 0) {
                buyNowUsed = true;
                // Если ставка выиграла по buyNow - отмечаем пользователя как победителя
                try {
                    depositService.markAsWinner(bidderId, auctionId);
                    log.info("User {} won auction {} via buy now", bidderId, auctionId);
                } catch (Exception e) {
                    log.error("Failed to mark user {} as winner: {}", bidderId, e.getMessage());
                }
                log.info("Аукцион завершен по цене мгновенной покупки! Победитель: {}", bidderId);
            } else {
                log.info("Ставка {} успешно принята, новая цена: {}, лидер: {}",
                        amount, auction.getCurrentPrice(), bidderId);
            }

            // Регистрируем участника в кэше
            registerBidder(auctionId, bidderId);

        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("Ошибка при создании ставки: {}", e.getMessage());

            // Если ставка не прошла и это была первая ставка - размораживаем депозит
            if (!hasExistingBids) {
                try {
                    depositService.unfreezeForAuction(bidderId, auctionId, auction.getStartingPrice());
                    log.info("Deposit unfrozen for user {} due to failed bid", bidderId);
                } catch (Exception ex) {
                    log.warn("Failed to unfreeze deposit for user {}: {}", bidderId, ex.getMessage());
                }
            }
            throw e;
        }

        // Сохраняем изменения
        Auction saved = auctionRepository.save(auction);
        log.debug("Аукцион сохранен, версия: {}", saved.getVersion());

        log.info("Ставка успешно создана! Итоговый статус аукциона: {}, продление: {}",
                saved.getStatus(), auctionExtended);

        return BidResponse.fromDomain(saved, amount, buyNowUsed, auctionExtended);
    }

    /**
     * Проверяет, делал ли пользователь уже ставки на этот аукцион
     */
    private boolean checkIfUserHasBids(Long auctionId, Long userId) {
        ConcurrentHashMap<Long, Boolean> bidders = auctionBidders.get(auctionId);
        return bidders != null && bidders.containsKey(userId);
    }

    /**
     * Регистрирует участника аукциона
     */
    private void registerBidder(Long auctionId, Long userId) {
        auctionBidders.computeIfAbsent(auctionId, k -> new ConcurrentHashMap<>())
                .put(userId, true);
        log.debug("User {} registered as bidder for auction {}", userId, auctionId);
    }

    /**
     * Получить список всех участников аукциона
     */
    public List<Long> getAuctionParticipants(Long auctionId) {
        ConcurrentHashMap<Long, Boolean> bidders = auctionBidders.get(auctionId);
        if (bidders == null) {
            return List.of();
        }
        return List.copyOf(bidders.keySet());
    }

    /**
     * Очистить участников аукциона (вызывается после завершения)
     */
    public void clearAuctionParticipants(Long auctionId) {
        auctionBidders.remove(auctionId);
        log.debug("Cleared participants for auction {}", auctionId);
    }
}