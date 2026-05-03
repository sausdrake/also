package com.example.autoauction.admin.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.dto.AuctionCreateRequest;
import com.example.autoauction.auction.web.dto.AuctionResponse;
import com.example.autoauction.vehicle.application.VehicleService;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.web.dto.VehicleResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AdminAuctionService {

    private final AuctionRepository auctionRepository;
    private final VehicleService vehicleService;
    private final AuctionValidator auctionValidator;

    public AdminAuctionService(AuctionRepository auctionRepository,
                               VehicleService vehicleService,
                               AuctionValidator auctionValidator) {
        this.auctionRepository = auctionRepository;
        this.vehicleService = vehicleService;
        this.auctionValidator = auctionValidator;
        log.info("AdminAuctionService инициализирован");
    }

    @Transactional
    public AuctionResponse createAuction(AuctionCreateRequest request, Long adminId) {
        log.info("=== СОЗДАНИЕ АУКЦИОНА ===");
        log.info("Запрос от adminId: {}, vehicleId: {}", adminId, request.vehicleId());

        // 1. Базовая валидация входных данных (проверка на null)
        auctionValidator.validateBasicRequest(request);

        // 2. Проверяем, существует ли автомобиль и имеет ли статус APPROVED
        VehicleResponse vehicle = getValidatedVehicle(request.vehicleId());

        // 3. Проверяем бизнес-правила (цены, шаг ставки, время) - ЭТО КЛЮЧЕВОЙ МОМЕНТ!
        auctionValidator.validateBusinessRules(request, vehicle);

        // 4. Проверяем, нет ли уже существующего аукциона
        checkExistingAuctions(request.vehicleId());

        // 5. Создаём и сохраняем аукцион
        return createAndSaveAuction(request, adminId, vehicle);
    }

    private void checkExistingAuctions(Long vehicleId) {
        List<AuctionStatus> forbiddenStatuses = List.of(
                AuctionStatus.ACTIVE,
                AuctionStatus.CREATED,
                AuctionStatus.SOLD
        );

        if (auctionRepository.existsByVehicleIdAndStatusIn(vehicleId, forbiddenStatuses)) {
            List<AuctionStatus> existingStatuses = auctionRepository.findStatusesByVehicleId(vehicleId);

            if (existingStatuses.contains(AuctionStatus.ACTIVE)) {
                throw new IllegalArgumentException("Автомобиль уже участвует в активном аукционе");
            }
            if (existingStatuses.contains(AuctionStatus.CREATED)) {
                throw new IllegalArgumentException("Для этого автомобиля уже создан аукцион");
            }
            if (existingStatuses.contains(AuctionStatus.SOLD)) {
                throw new IllegalArgumentException("Автомобиль уже был продан на аукционе");
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

        // Загружаем аукцион с пессимистичной блокировкой
        Auction auction = auctionRepository.findByIdWithLock(id)
                .orElseThrow(() -> {
                    log.error("Аукцион не найден: {}", id);
                    return new IllegalArgumentException("Аукцион с ID " + id + " не найден");
                });

        log.debug("Текущий статус аукциона: {}, текущая цена: {}, количество ставок: {}",
                auction.getStatus(), auction.getCurrentPrice(), auction.getTotalBids());

        // Проверяем, что аукцион активен
        if (auction.getStatus() != AuctionStatus.ACTIVE) {
            log.warn("Невозможно завершить аукцион в статусе: {}", auction.getStatus());
            throw new IllegalStateException(
                    String.format("Невозможно завершить аукцион в статусе '%s'. Только активные аукционы могут быть завершены",
                            auction.getStatus())
            );
        }

        // Проверяем, не истекло ли время окончания
        OffsetDateTime now = OffsetDateTime.now();
        if (auction.getEndTime().isAfter(now)) {
            log.warn("Попытка принудительного завершения аукциона до истечения времени. End time: {}, Now: {}",
                    auction.getEndTime(), now);
            // Можно либо разрешить, либо выбросить предупреждение
            // Разрешаем, но логируем
        }

        // Финализируем аукцион
        finalizeAuction(auction);

        // Сохраняем изменения
        Auction saved = auctionRepository.save(auction);
        log.info("Аукцион {} успешно завершен. Итоговый статус: {}, Победитель: {}, Выигрышная ставка: {}",
                saved.getId(), saved.getStatus(), saved.getWinnerId(), saved.getWinningBid());

        return AuctionResponse.fromDomain(saved);
    }

    /**
     * Внутренний метод для финализации аукциона
     */
    private void finalizeAuction(Auction auction) {
        // Проверяем, были ли ставки
        if (auction.getTotalBids() == null || auction.getTotalBids() == 0) {
            // Нет ставок - аукцион истек
            auction.setStatus(AuctionStatus.EXPIRED);
            auction.setWinnerId(null);
            auction.setWinningBid(null);
            log.info("Аукцион {} завершен без ставок", auction.getId());
            return;
        }

        // Есть ставки - проверяем резервную цену
        boolean reserveMet = auction.getReservePrice() == null ||
                auction.getCurrentPrice().compareTo(auction.getReservePrice()) >= 0;

        if (reserveMet) {
            // Резервная цена достигнута - победитель текущий лидер
            auction.setStatus(AuctionStatus.COMPLETED);
            log.info("Аукцион {} завершен успешно. Победитель: {}, Выигрышная ставка: {}, Резервная цена: {}",
                    auction.getId(), auction.getWinnerId(), auction.getWinningBid(), auction.getReservePrice());
        } else {
            // Резервная цена не достигнута - аукцион не состоялся
            auction.setStatus(AuctionStatus.EXPIRED);
            auction.setWinnerId(null);
            auction.setWinningBid(null);
            log.info("Аукцион {} завершен без продажи. Резервная цена {} не достигнута. Финальная цена: {}",
                    auction.getId(), auction.getReservePrice(), auction.getCurrentPrice());
        }

        auction.setUpdatedAt(OffsetDateTime.now());
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

        return auction;
    }

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
        auction.cancel();
        log.info("Аукцион отменен, новый статус: {}", auction.getStatus());

        Auction saved = auctionRepository.save(auction);
        if (saved == null) {
            throw new IllegalStateException("Не удалось сохранить аукцион после отмены");
        }
        return AuctionResponse.fromDomain(saved);
    }

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

    @Transactional(readOnly = true)
    public List<AuctionResponse> getAuctionsByVehicleId(Long vehicleId) {
        log.debug("=== ПОЛУЧЕНИЕ АУКЦИОНОВ ПО ID АВТОМОБИЛЯ: {} ===", vehicleId);
        return auctionRepository.findByVehicleId(vehicleId).stream()
                .map(AuctionResponse::fromDomain)
                .collect(Collectors.toList());
    }
}