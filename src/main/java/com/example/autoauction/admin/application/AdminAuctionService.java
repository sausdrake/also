// admin/application/AdminAuctionService.java
package com.example.autoauction.admin.application;

import com.example.autoauction.auction.application.AuctionCommitmentResolver;
import com.example.autoauction.auction.application.AuctionFinalizer;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.BidStepPolicy;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.dto.AuctionCreateRequest;
import com.example.autoauction.auction.web.dto.AuctionResponse;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.policy.domain.PolicyVersion;
import com.example.autoauction.user.application.UserService;
import com.example.autoauction.vehicle.application.VehicleService;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.web.dto.VehicleResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class AdminAuctionService {

    private final AuctionRepository auctionRepository;
    private final VehicleService vehicleService;
    private final AuctionValidator auctionValidator;
    private final AuctionFinalizer auctionFinalizer;
    private final SystemSettingsService settingsService;
    private final AuctionParticipantRepository participantRepository;
    private final DepositService depositService;
    private final UserService userService;
    private final PolicyVersionService policyVersionService;
    private final AuctionCommitmentResolver auctionCommitmentResolver;

    public AdminAuctionService(AuctionRepository auctionRepository,
                               VehicleService vehicleService,
                               AuctionValidator auctionValidator,
                               AuctionFinalizer auctionFinalizer,
                               SystemSettingsService settingsService,
                               AuctionParticipantRepository participantRepository,
                               DepositService depositService,
                               UserService userService,
                               PolicyVersionService policyVersionService,
                               AuctionCommitmentResolver auctionCommitmentResolver) {
        this.auctionFinalizer = auctionFinalizer;
        this.auctionRepository = auctionRepository;
        this.vehicleService = vehicleService;
        this.auctionValidator = auctionValidator;
        this.settingsService = settingsService;
        this.participantRepository = participantRepository;
        this.depositService = depositService;
        this.userService = userService;
        this.policyVersionService = policyVersionService;
        this.auctionCommitmentResolver = auctionCommitmentResolver;
        log.info("AdminAuctionService инициализирован");
    }

    @Transactional
    public AuctionResponse createAuction(AuctionCreateRequest request, Long adminId) {
        log.info("=== СОЗДАНИЕ АУКЦИОНА ===");
        log.info("Запрос от adminId: {}, vehicleId: {}", adminId, request.vehicleId());

        auctionValidator.validateBasicRequest(request);
        VehicleResponse vehicle = getValidatedVehicle(request.vehicleId());
        auctionValidator.validateBusinessRules(request, vehicle);
        checkExistingAuctions(request.vehicleId());

        // X3-C: a NEW Auction must never be persisted without an eligible commitment -
        // resolved and validated BEFORE the Auction is constructed, inside this same
        // transaction, so a resolution failure leaves no partial state whatsoever. Historical
        // NULL remains possible only for rows that predate this rule (never for anything
        // created through this method).
        Long sellerDiagnosticCommitmentId = auctionCommitmentResolver.resolveEligibleCommitment(request.vehicleId());

        return createAndSaveAuction(request, adminId, vehicle, sellerDiagnosticCommitmentId);
    }

    @SuppressWarnings("deprecation") // legacy COMPLETED auctions are treated as already-sold, per Slice 1a contract
    private void checkExistingAuctions(Long vehicleId) {
        // Slice 1a: WAITING_FOR_PAYMENT added (transient - clears automatically once the auction
        // resolves to SOLD/SALE_FAILED). SALE_FAILED deliberately NOT forbidden here - a failed
        // sale must not permanently block a legitimate re-auction; vehicle eligibility/status is
        // authoritative for that, not this auction-status guard (no new re-auction mechanism is
        // introduced in this slice). Legacy COMPLETED is forbidden, conservatively treated the
        // same as SOLD, since historical rows cannot prove whether a sale actually occurred.
        List<AuctionStatus> forbiddenStatuses = List.of(
                AuctionStatus.ACTIVE,
                AuctionStatus.CREATED,
                AuctionStatus.WAITING_FOR_PAYMENT,
                AuctionStatus.SOLD,
                AuctionStatus.COMPLETED
        );

        if (auctionRepository.existsByVehicleIdAndStatusIn(vehicleId, forbiddenStatuses)) {
            List<AuctionStatus> existingStatuses = auctionRepository.findStatusesByVehicleId(vehicleId);

            if (existingStatuses.contains(AuctionStatus.ACTIVE)) {
                throw new IllegalArgumentException("Автомобиль уже участвует в активном аукционе");
            }
            if (existingStatuses.contains(AuctionStatus.CREATED)) {
                throw new IllegalArgumentException("Для этого автомобиля уже создан аукцион");
            }
            if (existingStatuses.contains(AuctionStatus.WAITING_FOR_PAYMENT)) {
                throw new IllegalArgumentException("Аукцион для этого автомобиля ожидает подтверждения продажи");
            }
            if (existingStatuses.contains(AuctionStatus.SOLD)) {
                throw new IllegalArgumentException("Автомобиль уже был продан на аукционе");
            }
            if (existingStatuses.contains(AuctionStatus.COMPLETED)) {
                throw new IllegalArgumentException("Автомобиль уже участвовал в завершённом аукционе");
            }
        }
    }

    private AuctionResponse createAndSaveAuction(AuctionCreateRequest request, Long adminId, VehicleResponse vehicle,
                                                   Long sellerDiagnosticCommitmentId) {
        Auction auction = createAuctionFromRequest(request, adminId, vehicle, sellerDiagnosticCommitmentId);

        try {
            Auction saved = auctionRepository.save(auction);
            if (saved == null) {
                throw new IllegalStateException("Не удалось сохранить аукцион");
            }
            log.info("Аукцион успешно создан! ID: {}, vehicleId: {}, статус: {}",
                    saved.getId(), saved.getVehicleId(), saved.getStatus());
            return AuctionResponse.fromDomain(saved, resolveCommissionRate(saved));

        } catch (DataIntegrityViolationException e) {
            log.error("Конфликт при создании аукциона для vehicleId: {}", request.vehicleId());
            throw new DataIntegrityViolationException(
                    "Для этого автомобиля уже существует аукцион. Пожалуйста, проверьте статусы ACTIVE, CREATED или SOLD.",
                    e
            );
        }
    }

    @Transactional
    public AuctionResponse completeAuction(Long id, Long adminId) {
        log.info("=== ПРИНУДИТЕЛЬНОЕ ЗАВЕРШЕНИЕ АУКЦИОНА ===");
        log.info("ID аукциона: {}, Admin ID: {}", id, adminId);

        Auction auction = auctionRepository.findByIdWithLock(id)
                .orElseThrow(() -> {
                    log.error("Аукцион не найден: {}", id);
                    return new IllegalArgumentException("Аукцион с ID " + id + " не найден");
                });

        if (auction.getStatus() != AuctionStatus.ACTIVE) {
            throw new IllegalStateException(
                    String.format("Невозможно завершить аукцион в статусе '%s'. Только активные аукционы могут быть завершены",
                            auction.getStatus())
            );
        }

        auctionFinalizer.finalizeAuction(auction);
        return AuctionResponse.fromDomain(auction, resolveCommissionRate(auction));
    }

    private BigDecimal participationDeposit() {
        BigDecimal amount;
        try {
            amount = settingsService.getRequiredBigDecimal(SystemSettingsService.PARTICIPATION_DEPOSIT_KEY);
        } catch (IllegalStateException e) {
            amount = null;
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalStateException(
                    "Депозит для участия в аукционах не задан. Укажите его в разделе «Настройки».");
        }
        return amount;
    }

    private VehicleResponse getValidatedVehicle(Long vehicleId) {
        log.debug("Проверка автомобиля с ID: {}", vehicleId);

        VehicleResponse vehicle;
        try {
            vehicle = vehicleService.getVehicleDetails(vehicleId);
            if (vehicle == null) {
                throw new IllegalArgumentException("Автомобиль с ID " + vehicleId + " не найден");
            }
        } catch (IllegalArgumentException e) {
            log.error("Автомобиль с ID {} не найден", vehicleId);
            throw new IllegalArgumentException("Автомобиль с ID " + vehicleId + " не найден");
        }

        if (!VehicleStatus.APPROVED.equals(vehicle.status())) {
            log.error("Нельзя создать аукцион для автомобиля в статусе {}. Требуется статус APPROVED",
                    vehicle.status());
            throw new IllegalArgumentException(
                    String.format("Нельзя создать аукцион для автомобиля в статусе %s. Требуется статус APPROVED",
                            vehicle.status())
            );
        }

        log.debug("Автомобиль найден и одобрен: {} {} {} (VIN: {})",
                vehicle.brand(), vehicle.model(), vehicle.year(), vehicle.vin());

        return vehicle;
    }

    private Auction createAuctionFromRequest(AuctionCreateRequest request, Long adminId, VehicleResponse vehicle,
                                              Long sellerDiagnosticCommitmentId) {
        Auction auction = new Auction(
                request.vehicleId(),
                request.startingPrice(),
                request.reservePrice(),
                request.buyNowPrice(),
                BidStepPolicy.minimumBidStep(request.startingPrice()),   // AUCTION-01 (BD-02): never entered
                request.startTime(),
                request.endTime(),
                adminId
        );

        String vehicleInfo = String.format("%s %s %d (VIN: %s)",
                vehicle.brand(), vehicle.model(), vehicle.year(), vehicle.vin());
        auction.setVehicleInfo(vehicleInfo);

        // X3-C: immutable snapshot, assigned exactly once, here - never re-derived later from
        // Vehicle's current diagnosticRequestId pointer. See Auction.setSellerDiagnosticCommitmentId's
        // Javadoc for the single-call-site discipline.
        auction.setSellerDiagnosticCommitmentId(sellerDiagnosticCommitmentId);

        // DEPOSIT-01 (BD-01): the global participation deposit is the only source; it is snapshotted
        // here, so a later change of the setting never alters this auction or its frozen deposits.
        auction.setRequiredDepositAmount(participationDeposit());

        if (request.mainPhotoUrl() != null) {
            auction.setMainPhotoUrl(request.mainPhotoUrl());
        }
        if (request.additionalPhotoUrls() != null) {
            auction.setAdditionalPhotoUrls(request.additionalPhotoUrls());
        }

        // Slice 1b-2: every NEW auction is bound to the currently published PolicyVersion.
        // Fail closed (PolicyVersionService.getCurrentPublished() throws) if none is published -
        // never falls back to a live/global setting, a hardcoded rate, or a previous version.
        // The client cannot influence this: AuctionCreateRequest has no policyVersionId or
        // commissionRate field to bind to.
        PolicyVersion currentPolicy = policyVersionService.getCurrentPublished();
        auction.setPolicyVersionId(currentPolicy.getId());
        validatePolicyAwarePrices(auction, currentPolicy.getCommissionRate());

        return auction;
    }

    /**
     * Slice 1b-2 (B1 fix): the reserve-vs-startingPrice / buyNow-vs-startingPrice ordering
     * checks used to live in Auction's own constructor (unconditionally, comparing raw values).
     * They were moved here because the constructor cannot know, at construction time, whether
     * the auction being built will end up policy-bound - and after this slice, reservePrice/
     * buyNowPrice are SELLER-side amounts for a policy-bound auction while startingPrice remains
     * buyer-facing, so comparing them directly would wrongly reject valid auctions (e.g.
     * sellerReserve=99,000 with startingPrice=100,000 and a 2% commission yields a buyer-facing
     * reserve of 100,980 - valid, but 99,000 < 100,000 would incorrectly fail a raw comparison).
     *
     * This method is called once, from createAuctionFromRequest, strictly after
     * auction.setPolicyVersionId(...) has already been assigned - so it always takes the
     * policy-bound branch in production today. The legacy (policyVersionId == null) branch below
     * exists for correctness/completeness of this method as the single authoritative place this
     * comparison happens (there is currently no code path that creates a new legacy auction), and
     * is exercised directly by AdminAuctionServiceTest.
     */
    // Package-private (not private): exercised directly by AdminAuctionServiceTest for the
    // legacy (policyVersionId == null) branch, which no current production call path reaches -
    // createAuctionFromRequest always assigns a policy before calling this. See this method's
    // own Javadoc above.
    void validatePolicyAwarePrices(Auction auction, BigDecimal commissionRate) {
        BigDecimal comparableReserve;
        BigDecimal comparableBuyNow;

        if (auction.getPolicyVersionId() == null) {
            // Legacy: reservePrice/buyNowPrice are already on the same (buyer-facing) scale as
            // startingPrice - compare the raw stored values directly, exactly as Auction's own
            // constructor did before this check moved out of the domain object.
            comparableReserve = auction.getReservePrice();
            comparableBuyNow = auction.getBuyNowPrice();
        } else {
            // Policy-bound: reservePrice/buyNowPrice are seller-side - resolve the buyer-facing
            // values via Auction's own resolver methods (the single authoritative formula) and
            // compare those against startingPrice instead.
            comparableReserve = auction.resolveBuyerFacingReservePrice(commissionRate);
            comparableBuyNow = auction.resolveBuyerFacingBuyNowPrice(commissionRate);
        }

        if (comparableReserve != null && comparableReserve.compareTo(auction.getStartingPrice()) < 0) {
            throw new IllegalArgumentException(
                    String.format("Резервная цена (%s) не может быть меньше стартовой цены (%s)",
                            comparableReserve, auction.getStartingPrice()));
        }

        if (comparableBuyNow != null && comparableBuyNow.compareTo(auction.getStartingPrice()) <= 0) {
            throw new IllegalArgumentException(
                    String.format("Цена мгновенной покупки (%s) должна быть больше стартовой цены (%s)",
                            comparableBuyNow, auction.getStartingPrice()));
        }
    }

    /**
     * Slice 1b-2: resolves the commissionRate needed to compute buyer-facing values for an
     * already-persisted auction - null for legacy auctions (Auction's own resolver methods
     * short-circuit before touching it), the specific immutable PolicyVersion this exact
     * auction was created under otherwise. Never getCurrentPublished() - an existing auction's
     * commission is fixed forever at creation time, regardless of what is published later.
     */
    private BigDecimal resolveCommissionRate(Auction auction) {
        if (auction.getPolicyVersionId() == null) {
            return null;
        }
        return policyVersionService.getById(auction.getPolicyVersionId()).getCommissionRate();
    }

    // ============ ОБНОВЛЁННЫЕ МЕТОДЫ С ПАГИНАЦИЕЙ ============

    @Transactional(readOnly = true)
    public Page<AuctionResponse> getAllAuctions(Pageable pageable) {
        log.debug("=== ПОЛУЧЕНИЕ ВСЕХ АУКЦИОНОВ С ПАГИНАЦИЕЙ ===");
        return auctionRepository.findAllWithPagination(pageable)
                .map(auction -> AuctionResponse.fromDomain(auction, resolveCommissionRate(auction)));
    }

    // Старый метод для обратной совместимости (если нужен)
    @Transactional(readOnly = true)
    public List<AuctionResponse> getAllAuctions() {
        log.debug("=== ПОЛУЧЕНИЕ ВСЕХ АУКЦИОНОВ ===");
        return auctionRepository.findAll().stream()
                .map(auction -> AuctionResponse.fromDomain(auction, resolveCommissionRate(auction)))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AuctionResponse getAuction(Long id) {
        log.debug("=== ПОЛУЧЕНИЕ АУКЦИОНА ПО ID: {} ===", id);
        return auctionRepository.findById(id)
                .map(auction -> {
                    log.debug("Аукцион найден: ID={}, vehicleId={}, статус={}",
                            auction.getId(), auction.getVehicleId(), auction.getStatus());
                    return AuctionResponse.fromDomain(auction, resolveCommissionRate(auction));
                })
                .orElseThrow(() -> {
                    log.error("Аукцион не найден: {}", id);
                    return new IllegalArgumentException("Аукцион с ID " + id + " не найден");
                });
    }

    @Transactional
    public AuctionResponse startAuction(Long id, Long adminId) {
        log.info("=== ЗАПУСК АУКЦИОНА ===");
        log.info("ID аукциона: {}, adminId: {}", id, adminId);

        Auction auction = auctionRepository.findByIdWithLock(id)
                .orElseThrow(() -> {
                    log.error("Аукцион не найден: {}", id);
                    return new IllegalArgumentException("Аукцион с ID " + id + " не найден");
                });

        if (auction.getStatus() == AuctionStatus.ACTIVE) {
            throw new IllegalStateException("Аукцион уже запущен");
        }

        log.debug("Текущий статус аукциона: {}", auction.getStatus());
        // Vehicle Auction-State Wiring: delegates the actual CREATED->ACTIVE transition (and
        // the atomic Vehicle APPROVED->IN_AUCTION transition that must accompany it) to
        // AuctionFinalizer.startAuction, the single shared owner of this logic - matching how
        // cancelAuction()/completeAuction() below already delegate to auctionFinalizer instead
        // of duplicating transition logic here. The ACTIVE pre-check above is kept exactly as
        // it was (not folded into the shared method) to preserve this method's existing,
        // test-verified "Аукцион уже запущен" message specifically for that one status - every
        // other invalid status still falls through to Auction.start()'s own generic message,
        // unchanged.
        auctionFinalizer.startAuction(auction);
        log.info("Аукцион запущен, новый статус: {}", auction.getStatus());

        return AuctionResponse.fromDomain(auction, resolveCommissionRate(auction));
    }

    @Transactional
    public AuctionResponse cancelAuction(Long id, Long adminId) {
        log.info("=== ОТМЕНА АУКЦИОНА ===");
        log.info("ID аукциона: {}, adminId: {}", id, adminId);

        Auction auction = auctionRepository.findByIdWithLock(id)
                .orElseThrow(() -> {
                    log.error("Аукцион не найден: {}", id);
                    return new IllegalArgumentException("Аукцион с ID " + id + " не найден");
                });

        log.debug("Текущий статус аукциона: {}", auction.getStatus());

        if (!auction.isCancellable()) {
            throw new IllegalStateException(
                    String.format("Невозможно отменить аукцион в статусе '%s'. Только аукционы со статусами 'CREATED' или 'ACTIVE' могут быть отменены",
                            auction.getStatus()));
        }

        auctionFinalizer.cancelAuction(auction);
        log.info("Аукцион отменен, новый статус: {}", auction.getStatus());

        return AuctionResponse.fromDomain(auction, resolveCommissionRate(auction));
    }

    // Обновлённый метод с пагинацией
    @Transactional(readOnly = true)
    public Page<AuctionResponse> getAuctionsByStatus(String status, Pageable pageable) {
        try {
            AuctionStatus auctionStatus = AuctionStatus.valueOf(status.toUpperCase());
            return auctionRepository.findByStatusWithPagination(auctionStatus, pageable)
                    .map(auction -> AuctionResponse.fromDomain(auction, resolveCommissionRate(auction)));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Неверный статус аукциона: " + status);
        }
    }

    // Старый метод для обратной совместимости
    @Transactional(readOnly = true)
    public List<AuctionResponse> getAuctionsByStatus(String status) {
        try {
            AuctionStatus auctionStatus = AuctionStatus.valueOf(status.toUpperCase());
            return auctionRepository.findByStatus(auctionStatus).stream()
                    .map(auction -> AuctionResponse.fromDomain(auction, resolveCommissionRate(auction)))
                    .collect(Collectors.toList());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Неверный статус аукциона: " + status);
        }
    }

    @Transactional
    public AuctionResponse confirmAuctionStart(Long id) {
        int confirmHours = settingsService.getInt("auction_confirm_hours", 2);
        Auction auction = auctionRepository.findByIdWithLock(id)
                .orElseThrow(() -> new IllegalArgumentException("Аукцион с ID " + id + " не найден"));
        auction.confirmStart(confirmHours);
        Auction saved = auctionRepository.save(auction);
        return AuctionResponse.fromDomain(saved, resolveCommissionRate(saved));
    }

    @Transactional
    public List<AuctionResponse> confirmAuctionStartBulk(List<Long> ids) {
        int confirmHours = settingsService.getInt("auction_confirm_hours", 2);
        return auctionRepository.findAllById(ids).stream()
                .map(auction -> {
                    auction.confirmStart(confirmHours);
                    Auction saved = auctionRepository.save(auction);
                    return AuctionResponse.fromDomain(saved, resolveCommissionRate(saved));
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AuctionResponse> getAuctionsByVehicleId(Long vehicleId) {
        log.debug("=== ПОЛУЧЕНИЕ АУКЦИОНОВ ПО ID АВТОМОБИЛЯ: {} ===", vehicleId);
        return auctionRepository.findByVehicleId(vehicleId).stream()
                .map(auction -> AuctionResponse.fromDomain(auction, resolveCommissionRate(auction)))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getParticipants(Long auctionId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Аукцион не найден: " + auctionId));
        List<Long> userIds = participantRepository.getParticipants(auctionId);
        return userIds.stream().map(userId -> {
            Map<String, Object> entry = new HashMap<>();
            entry.put("userId", userId);
            userService.getUser(userId).ifPresent(u -> {
                entry.put("username", u.getUsername());
                entry.put("email", u.getEmail());
            });
            entry.put("frozenAmount", depositService.getFrozenAmountForAuction(userId, auctionId));
            entry.put("isWinner", userId.equals(auction.getWinnerId()));
            return entry;
        }).collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> forceUnfreezeAll(Long auctionId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Аукцион не найден: " + auctionId));
        // BALANCE-01: a lot that still takes bids (ACTIVE) or Buy Now (CREATED) must keep every
        // participant's deposit frozen - BidService treats an existing participant row as "deposit
        // already frozen", so unfreezing here would let that participant keep bidding without one.
        if (auction.getStatus() == AuctionStatus.CREATED || auction.getStatus() == AuctionStatus.ACTIVE) {
            throw new IllegalStateException(String.format(
                    "Нельзя принудительно размораживать депозиты аукциона в статусе '%s': аукцион ещё принимает ставки",
                    auction.getStatus()));
        }
        List<Long> userIds = participantRepository.getParticipants(auctionId);
        int count = 0;
        for (Long userId : userIds) {
            if (userId.equals(auction.getWinnerId())) continue;
            try {
                depositService.unfreezeForAuction(userId, auctionId);
                count++;
            } catch (Exception e) {
                log.warn("Could not unfreeze deposit for user {} auction {}: {}", userId, auctionId, e.getMessage());
            }
        }
        log.info("Force-unfroze {} participants for auction {}", count, auctionId);
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Разморожено участников: " + count);
        result.put("count", count);
        return result;
    }

    /**
     * X3-D hardening: rejects release while the auction's outcome is not yet known
     * (ACTIVE - winnerId is only the current leading bidder, not final; WAITING_FOR_PAYMENT -
     * the sale itself is not yet confirmed one way or the other). Releasing here would let a
     * bidder withdraw their collateral before a later outcome (e.g. BUYER_FAULT) could ever
     * confiscate it. Every other status - legacy COMPLETED, SOLD, SALE_FAILED, EXPIRED,
     * CANCELLED - remains allowed exactly as before: legacy COMPLETED predates SaleResolution
     * entirely and must keep working; SOLD/SALE_FAILED are safe because the underlying frozen
     * row is a physical delete on first release (whether performed automatically by X3-D's own
     * outcome resolution or manually here) - a second call against an already-released row
     * fails closed on its own (existing "Заморозка победителя... не найдена" guard), never a
     * silent double-release. Deliberately does NOT also require SaleResolution to exist -
     * legacy COMPLETED rows have none and must not be locked out by such a check.
     */
    @Transactional
    public void releaseWinner(Long auctionId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Аукцион не найден: " + auctionId));
        if (auction.getStatus() == AuctionStatus.ACTIVE || auction.getStatus() == AuctionStatus.WAITING_FOR_PAYMENT) {
            throw new IllegalStateException(
                    String.format("Невозможно освободить депозит победителя аукциона %d в статусе '%s' - исход ещё не определён",
                            auctionId, auction.getStatus())
            );
        }
        Long winnerId = auction.getWinnerId();
        if (winnerId == null) {
            throw new IllegalStateException("У аукциона " + auctionId + " нет победителя");
        }
        depositService.releaseWinnerDeposit(winnerId, auctionId);
        log.info("Winner deposit released for auction {}, winner {}", auctionId, winnerId);
    }
}