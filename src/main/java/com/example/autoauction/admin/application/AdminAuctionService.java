// admin/application/AdminAuctionService.java
package com.example.autoauction.admin.application;

import com.example.autoauction.auction.application.AuctionFinalizer;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.dto.AuctionCreateRequest;
import com.example.autoauction.auction.web.dto.AuctionResponse;
import com.example.autoauction.deposit.application.DepositService;
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

    public AdminAuctionService(AuctionRepository auctionRepository,
                               VehicleService vehicleService,
                               AuctionValidator auctionValidator,
                               AuctionFinalizer auctionFinalizer,
                               SystemSettingsService settingsService,
                               AuctionParticipantRepository participantRepository,
                               DepositService depositService,
                               UserService userService) {
        this.auctionFinalizer = auctionFinalizer;
        this.auctionRepository = auctionRepository;
        this.vehicleService = vehicleService;
        this.auctionValidator = auctionValidator;
        this.settingsService = settingsService;
        this.participantRepository = participantRepository;
        this.depositService = depositService;
        this.userService = userService;
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

        return createAndSaveAuction(request, adminId, vehicle);
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

    private AuctionResponse createAndSaveAuction(AuctionCreateRequest request, Long adminId, VehicleResponse vehicle) {
        Auction auction = createAuctionFromRequest(request, adminId, vehicle);

        try {
            Auction saved = auctionRepository.save(auction);
            if (saved == null) {
                throw new IllegalStateException("Не удалось сохранить аукцион");
            }
            log.info("Аукцион успешно создан! ID: {}, vehicleId: {}, статус: {}",
                    saved.getId(), saved.getVehicleId(), saved.getStatus());
            return AuctionResponse.fromDomain(saved);

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
        return AuctionResponse.fromDomain(auction);
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

    private Auction createAuctionFromRequest(AuctionCreateRequest request, Long adminId, VehicleResponse vehicle) {
        Auction auction = new Auction(
                request.vehicleId(),
                request.startingPrice(),
                request.reservePrice(),
                request.buyNowPrice(),
                request.minBidStep(),
                request.startTime(),
                request.endTime(),
                adminId
        );

        String vehicleInfo = String.format("%s %s %d (VIN: %s)",
                vehicle.brand(), vehicle.model(), vehicle.year(), vehicle.vin());
        auction.setVehicleInfo(vehicleInfo);

        if (request.requiredDepositAmount() != null) {
            auction.setRequiredDepositAmount(request.requiredDepositAmount());
        }

        if (request.mainPhotoUrl() != null) {
            auction.setMainPhotoUrl(request.mainPhotoUrl());
        }
        if (request.additionalPhotoUrls() != null) {
            auction.setAdditionalPhotoUrls(request.additionalPhotoUrls());
        }

        return auction;
    }

    // ============ ОБНОВЛЁННЫЕ МЕТОДЫ С ПАГИНАЦИЕЙ ============

    @Transactional(readOnly = true)
    public Page<AuctionResponse> getAllAuctions(Pageable pageable) {
        log.debug("=== ПОЛУЧЕНИЕ ВСЕХ АУКЦИОНОВ С ПАГИНАЦИЕЙ ===");
        return auctionRepository.findAllWithPagination(pageable).map(AuctionResponse::fromDomain);
    }

    // Старый метод для обратной совместимости (если нужен)
    @Transactional(readOnly = true)
    public List<AuctionResponse> getAllAuctions() {
        log.debug("=== ПОЛУЧЕНИЕ ВСЕХ АУКЦИОНОВ ===");
        return auctionRepository.findAll().stream()
                .map(AuctionResponse::fromDomain)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AuctionResponse getAuction(Long id) {
        log.debug("=== ПОЛУЧЕНИЕ АУКЦИОНА ПО ID: {} ===", id);
        return auctionRepository.findById(id)
                .map(auction -> {
                    log.debug("Аукцион найден: ID={}, vehicleId={}, статус={}",
                            auction.getId(), auction.getVehicleId(), auction.getStatus());
                    return AuctionResponse.fromDomain(auction);
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
        auction.start();
        log.info("Аукцион запущен, новый статус: {}", auction.getStatus());

        Auction saved = auctionRepository.save(auction);
        if (saved == null) {
            throw new IllegalStateException("Не удалось сохранить аукцион после запуска");
        }
        return AuctionResponse.fromDomain(saved);
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

        return AuctionResponse.fromDomain(auction);
    }

    // Обновлённый метод с пагинацией
    @Transactional(readOnly = true)
    public Page<AuctionResponse> getAuctionsByStatus(String status, Pageable pageable) {
        try {
            AuctionStatus auctionStatus = AuctionStatus.valueOf(status.toUpperCase());
            return auctionRepository.findByStatusWithPagination(auctionStatus, pageable)
                    .map(AuctionResponse::fromDomain);
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
                    .map(AuctionResponse::fromDomain)
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
        return AuctionResponse.fromDomain(auctionRepository.save(auction));
    }

    @Transactional
    public List<AuctionResponse> confirmAuctionStartBulk(List<Long> ids) {
        int confirmHours = settingsService.getInt("auction_confirm_hours", 2);
        return auctionRepository.findAllById(ids).stream()
                .map(auction -> {
                    auction.confirmStart(confirmHours);
                    return AuctionResponse.fromDomain(auctionRepository.save(auction));
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AuctionResponse> getAuctionsByVehicleId(Long vehicleId) {
        log.debug("=== ПОЛУЧЕНИЕ АУКЦИОНОВ ПО ID АВТОМОБИЛЯ: {} ===", vehicleId);
        return auctionRepository.findByVehicleId(vehicleId).stream()
                .map(AuctionResponse::fromDomain)
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

    @Transactional
    public void releaseWinner(Long auctionId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Аукцион не найден: " + auctionId));
        Long winnerId = auction.getWinnerId();
        if (winnerId == null) {
            throw new IllegalStateException("У аукциона " + auctionId + " нет победителя");
        }
        depositService.releaseWinnerDeposit(winnerId, auctionId);
        log.info("Winner deposit released for auction {}, winner {}", auctionId, winnerId);
    }
}