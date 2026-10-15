// auction/application/BidService.java (добавлена интеграция с WebSocket)
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.dto.BidResponse;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.user.application.UserService;
import com.example.autoauction.user.domain.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;
    private final PolicyVersionService policyVersionService;
    private final UserService userService;
    private final AuctionSellerResolver auctionSellerResolver;

    public BidService(AuctionRepository auctionRepository,
                      DepositService depositService,
                      AuctionParticipantRepository participantRepository,
                      AuctionFinalizer auctionFinalizer,
                      RateLimitService rateLimitService,
                      ApplicationEventPublisher eventPublisher,
                      PolicyVersionService policyVersionService,
                      UserService userService,
                      AuctionSellerResolver auctionSellerResolver) {
        this.auctionRepository = auctionRepository;
        this.depositService = depositService;
        this.participantRepository = participantRepository;
        this.auctionFinalizer = auctionFinalizer;
        this.rateLimitService = rateLimitService;
        this.eventPublisher = eventPublisher;
        this.policyVersionService = policyVersionService;
        this.userService = userService;
        this.auctionSellerResolver = auctionSellerResolver;
        log.info("BidService initialized with WebSocket and Notification support");
    }

    /**
     * Slice 1A: fail-closed controlled-beta trust gate for the protected participant actions
     * (bidding, Buy Now). Reused by both placeBid() and buyNow() rather than duplicated. Checked
     * before any auction lookup/deposit freeze/participant registration - an untrusted caller's
     * request is rejected with zero side effects. Uses SecurityException, the established
     * convention in this codebase for "this actor is not permitted to do this" application-layer
     * rejections (mirrors VehicleService/DiagnosticRequestService), mapped to 403 by
     * GlobalExceptionHandler.
     */
    private void requireTrustedParticipant(Long userId) {
        User user = userService.getUser(userId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + userId));
        if (!user.isTrusted()) {
            throw new SecurityException(
                    "Участие в торгах доступно только доверенным пользователям. "
                            + "Обратитесь к администратору для получения доступа.");
        }
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

        // Slice 1A: fail-closed before any auction lookup/deposit/participant side effect.
        requireTrustedParticipant(buyerId);

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

        // SEC-09: createdBy is the administrator who created the auction; the seller (vehicle
        // owner) is resolved separately - neither may buy the lot.
        if (buyerId.equals(auction.getCreatedBy()) || auctionSellerResolver.isSeller(auction, buyerId)) {
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

        // Slice 1A: fail-closed before any auction lookup/deposit/participant side effect.
        requireTrustedParticipant(bidderId);

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

        // SEC-09: the seller (vehicle owner) must not bid up their own lot; createdBy (the admin who
        // created the auction) stays excluded as before.
        if (bidderId.equals(auction.getCreatedBy()) || auctionSellerResolver.isSeller(auction, bidderId)) {
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

            depositService.freezeForAuction(bidderId, auctionId, requiredDeposit);
            log.info("Deposit frozen for user {} on auction {}", bidderId, auctionId);

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

        // Deferred to AFTER_COMMIT (see BidPlacedEventListener): a retried/rolled-back attempt's
        // WebSocket/notification side effects must never fire, and a WS/notification failure must
        // never invalidate an already-committed bid (MVP-001).
        eventPublisher.publishEvent(new BidPlacedEvent(saved, bidderId, amount, prevLeaderId, auctionExtended));

        log.info("Ставка успешно создана! Итоговый статус аукциона: {}, продление: {}",
                saved.getStatus(), auctionExtended);

        return BidResponse.fromDomain(saved, amount, false, auctionExtended);
    }

    @Transactional(readOnly = true)
    public List<Long> getAuctionParticipants(Long auctionId) {
        return participantRepository.getParticipants(auctionId);
    }
}