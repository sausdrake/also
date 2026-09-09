package com.example.autoauction.vehicle.application;

import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import com.example.autoauction.vehicle.web.dto.VehicleCreateRequest;
import com.example.autoauction.vehicle.web.dto.VehicleUpdateRequest;
import com.example.autoauction.vehicle.web.dto.VehicleResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VehicleService {

    private final VehicleRepository vehicleRepository;

    public VehicleService(VehicleRepository vehicleRepository) {
        this.vehicleRepository = vehicleRepository;
        log.info("VehicleService инициализирован");
    }

    @Transactional
    public VehicleResponse createDraft(VehicleCreateRequest request, Long diagnosticId, String diagnosticName) {
        log.info("=== СОЗДАНИЕ ЧЕРНОВИКА АВТОМОБИЛЯ ===");
        log.info("Диагностик: {} (ID: {}), VIN: {}", diagnosticName, diagnosticId, request.vin());
        log.debug("Детали запроса: brand={}, model={}, year={}, type={}",
                request.brand(), request.model(), request.year(), request.type());
        Objects.requireNonNull(diagnosticId, "diagnosticId не может быть null");
        Objects.requireNonNull(diagnosticName, "diagnosticName не может быть null");
        // Проверяем уникальность VIN
        log.debug("Проверка уникальности VIN: {}", request.vin());
        if (vehicleRepository.existsByVin(request.vin())) {
            log.error("Автомобиль с VIN {} уже существует", request.vin());
            throw new IllegalArgumentException("Автомобиль с таким VIN уже существует");
        }
        log.debug("VIN уникален");

        Vehicle vehicle = new Vehicle(
                request.brand(),
                request.model(),
                request.year(),
                request.vin(),
                request.type(),
                diagnosticId,
                diagnosticName
        );

        // Заполняем опциональные поля
        if (request.licensePlate() != null) {
            vehicle.setLicensePlate(request.licensePlate());
            log.debug("Установлен госномер: {}", request.licensePlate());
        }
        if (request.color() != null) {
            vehicle.setColor(request.color());
            log.debug("Установлен цвет: {}", request.color());
        }
        if (request.mileage() != null) {
            vehicle.setMileage(request.mileage());
            log.debug("Установлен пробег: {}", request.mileage());
        }
        if (request.engineCapacity() != null) {
            vehicle.setEngineCapacity(request.engineCapacity());
            log.debug("Установлен объем двигателя: {}", request.engineCapacity());
        }
        if (request.fuelType() != null) {
            vehicle.setFuelType(request.fuelType());
            log.debug("Установлен тип топлива: {}", request.fuelType());
        }
        if (request.transmission() != null) {
            vehicle.setTransmission(request.transmission());
            log.debug("Установлена коробка передач: {}", request.transmission());
        }
        if (request.description() != null) {
            vehicle.setDescription(request.description());
            log.debug("Установлено описание");
        }

        log.debug("Сохранение автомобиля в БД");
        Vehicle saved = vehicleRepository.save(vehicle);
        log.info("Автомобиль успешно создан! ID: {}, статус: {}", saved.getId(), saved.getStatus());

        return VehicleResponse.fromDomain(saved);
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> getMyVehicles(Long diagnosticId, VehicleStatus status) {
        log.debug("=== ПОЛУЧЕНИЕ АВТОМОБИЛЕЙ ДИАГНОСТИКА ID: {} ===", diagnosticId);
        List<VehicleResponse> vehicles;

        if (status != null) {
            log.debug("Фильтр по статусу: {}", status);
            vehicles = vehicleRepository.findByDiagnosticIdAndStatus(diagnosticId, status).stream()
                    .map(VehicleResponse::fromDomain)
                    .collect(Collectors.toList());
        } else {
            vehicles = vehicleRepository.findByDiagnosticId(diagnosticId).stream()
                    .map(VehicleResponse::fromDomain)
                    .collect(Collectors.toList());
        }

        log.debug("Найдено автомобилей: {}", vehicles.size());
        return vehicles;
    }

    @Transactional(readOnly = true)
    public VehicleResponse getVehicle(Long id, Long diagnosticId) {
        log.debug("=== ПОЛУЧЕНИЕ АВТОМОБИЛЯ ID: {} ДИАГНОСТИКОМ ID: {} ===", id, diagnosticId);

        Vehicle vehicle = vehicleRepository.findById(id)
                .orElseThrow(() -> {
                    log.error("Автомобиль не найден: {}", id);
                    return new IllegalArgumentException("Автомобиль не найден: " + id);
                });

        log.debug("Автомобиль найден: {} {} {}, статус: {}",
                vehicle.getBrand(), vehicle.getModel(), vehicle.getYear(), vehicle.getStatus());

        // Проверяем, что автомобиль принадлежит диагностику
        if (!vehicle.getDiagnosticId().equals(diagnosticId)) {
            log.error("Доступ запрещен: автомобиль принадлежит диагностику ID: {}, текущий ID: {}",
                    vehicle.getDiagnosticId(), diagnosticId);
            throw new SecurityException("У вас нет доступа к этому автомобилю");
        }
        log.debug("Проверка доступа пройдена");

        return VehicleResponse.fromDomain(vehicle);
    }

    @Transactional
    public VehicleResponse updateDraft(Long id, VehicleUpdateRequest request, Long diagnosticId) {
        log.info("=== ОБНОВЛЕНИЕ ЧЕРНОВИКА ID: {} ===", id);
        log.info("Диагностик ID: {}", diagnosticId);

        Vehicle vehicle = vehicleRepository.findById(id)
                .orElseThrow(() -> {
                    log.error("Автомобиль не найден: {}", id);
                    return new IllegalArgumentException("Автомобиль не найден: " + id);
                });

        log.debug("Текущий статус автомобиля: {}", vehicle.getStatus());

        // Проверяем права доступа
        if (!vehicle.getDiagnosticId().equals(diagnosticId)) {
            log.error("Доступ запрещен: автомобиль принадлежит диагностику ID: {}",
                    vehicle.getDiagnosticId());
            throw new SecurityException("У вас нет доступа к этому автомобилю");
        }

        // Проверяем статус
        if (vehicle.getStatus() != VehicleStatus.DRAFT && vehicle.getStatus() != VehicleStatus.NEEDS_FIXES) {
            log.error("Нельзя редактировать автомобиль в статусе: {}", vehicle.getStatus());
            throw new IllegalStateException("Нельзя редактировать автомобиль в статусе: " + vehicle.getStatus());
        }

        // Обновляем только те поля, которые пришли в запросе
        if (request.licensePlate() != null) {
            vehicle.setLicensePlate(request.licensePlate());
            log.debug("Обновлен госномер: {}", request.licensePlate());
        }
        if (request.color() != null) {
            vehicle.setColor(request.color());
            log.debug("Обновлен цвет: {}", request.color());
        }
        if (request.mileage() != null) {
            vehicle.setMileage(request.mileage());
            log.debug("Обновлен пробег: {}", request.mileage());
        }
        if (request.engineCapacity() != null) {
            vehicle.setEngineCapacity(request.engineCapacity());
            log.debug("Обновлен объем двигателя: {}", request.engineCapacity());
        }
        if (request.fuelType() != null) {
            vehicle.setFuelType(request.fuelType());
            log.debug("Обновлен тип топлива: {}", request.fuelType());
        }
        if (request.transmission() != null) {
            vehicle.setTransmission(request.transmission());
            log.debug("Обновлена коробка передач: {}", request.transmission());
        }
        if (request.description() != null) {
            vehicle.setDescription(request.description());
            log.debug("Обновлено описание");
        }

        vehicle.setUpdatedAt(OffsetDateTime.now());
        log.debug("Сохранение изменений в БД");

        Vehicle updated = vehicleRepository.save(vehicle);
        log.info("Автомобиль обновлен, статус: {}", updated.getStatus());

        return VehicleResponse.fromDomain(updated);
    }

    @Transactional
    public VehicleResponse submitForReview(Long id, Long diagnosticId) {
        log.info("=== ОТПРАВКА НА ПРОВЕРКУ АВТОМОБИЛЯ ID: {} ===", id);
        log.info("Диагностик ID: {}", diagnosticId);

        Vehicle vehicle = vehicleRepository.findById(id)
                .orElseThrow(() -> {
                    log.error("Автомобиль не найден: {}", id);
                    return new IllegalArgumentException("Автомобиль не найден: " + id);
                });

        log.debug("Текущий статус: {}", vehicle.getStatus());

        if (!vehicle.getDiagnosticId().equals(diagnosticId)) {
            log.error("Доступ запрещен: автомобиль принадлежит диагностику ID: {}",
                    vehicle.getDiagnosticId());
            throw new SecurityException("У вас нет доступа к этому автомобилю");
        }

        vehicle.submitForReview();
        log.info("Автомобиль отправлен на проверку, новый статус: PENDING_REVIEW");

        Vehicle updated = vehicleRepository.save(vehicle);
        log.debug("Автомобиль сохранен в БД");

        return VehicleResponse.fromDomain(updated);
    }

    @Transactional
    public void deleteDraft(Long id, Long diagnosticId) {
        log.info("=== УДАЛЕНИЕ ЧЕРНОВИКА АВТОМОБИЛЯ ID: {} ===", id);
        log.info("Диагностик ID: {}", diagnosticId);

        Vehicle vehicle = vehicleRepository.findById(id)
                .orElseThrow(() -> {
                    log.error("Автомобиль не найден: {}", id);
                    return new IllegalArgumentException("Автомобиль не найден: " + id);
                });

        log.debug("Текущий статус: {}", vehicle.getStatus());

        if (!vehicle.getDiagnosticId().equals(diagnosticId)) {
            log.error("Доступ запрещен: автомобиль принадлежит диагностику ID: {}",
                    vehicle.getDiagnosticId());
            throw new SecurityException("У вас нет доступа к этому автомобилю");
        }

        if (vehicle.getStatus() != VehicleStatus.DRAFT) {
            log.error("Нельзя удалить автомобиль в статусе: {}. Можно удалить только черновик",
                    vehicle.getStatus());
            throw new IllegalStateException("Можно удалить только черновик");
        }

        vehicleRepository.deleteById(id);
        log.info("Автомобиль успешно удален");
    }

    // ========== МЕТОДЫ ДЛЯ АДМИНА ==========

    @Transactional(readOnly = true)
    public List<VehicleResponse> getVehiclesByStatus(VehicleStatus status) {
        log.debug("=== ПОЛУЧЕНИЕ АВТОМОБИЛЕЙ ПО СТАТУСУ: {} ===", status);
        List<VehicleResponse> vehicles = vehicleRepository.findByStatus(status).stream()
                .map(VehicleResponse::fromDomain)
                .collect(Collectors.toList());
        log.debug("Найдено автомобилей: {}", vehicles.size());
        return vehicles;
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> getVehiclesByStatuses(List<VehicleStatus> statuses) {
        log.debug("=== ПОЛУЧЕНИЕ АВТОМОБИЛЕЙ ПО СПИСКУ СТАТУСОВ: {} ===", statuses);
        List<VehicleResponse> vehicles = vehicleRepository.findByStatusIn(statuses).stream()
                .map(VehicleResponse::fromDomain)
                .collect(Collectors.toList());
        log.debug("Найдено автомобилей: {}", vehicles.size());
        return vehicles;
    }

    @Transactional(readOnly = true)
    public VehicleResponse getVehicleDetails(Long id) {
        log.debug("=== ПОЛУЧЕНИЕ ДЕТАЛЕЙ АВТОМОБИЛЯ ID: {} ===", id);
        Vehicle vehicle = vehicleRepository.findById(id)
                .orElseThrow(() -> {
                    log.error("Автомобиль не найден: {}", id);
                    return new IllegalArgumentException("Автомобиль не найден: " + id);
                });

        log.debug("Автомобиль: {} {} {}, статус: {}, диагностик ID: {}",
                vehicle.getBrand(), vehicle.getModel(), vehicle.getYear(),
                vehicle.getStatus(), vehicle.getDiagnosticId());

        return VehicleResponse.fromDomain(vehicle);
    }

    @Transactional
    public VehicleResponse approveVehicle(Long vehicleId, Long adminId, String adminName) {
        log.info("=== ОДОБРЕНИЕ АВТОМОБИЛЯ ID: {} ===", vehicleId);
        log.info("Администратор: {} (ID: {})", adminName, adminId);

        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> {
                    log.error("Автомобиль не найден: {}", vehicleId);
                    return new IllegalArgumentException("Автомобиль не найден: " + vehicleId);
                });

        log.debug("Текущий статус: {}, диагностик: {}",
                vehicle.getStatus(), vehicle.getDiagnosticName());

        vehicle.approve(adminId, adminName);
        log.info("Автомобиль одобрен, новый статус: APPROVED");

        Vehicle updated = vehicleRepository.save(vehicle);
        log.debug("Автомобиль сохранен в БД");

        return VehicleResponse.fromDomain(updated);
    }

    /**
     * Vehicle Auction-State Wiring: transitions a Vehicle from APPROVED to IN_AUCTION when its
     * auction actually starts. Deliberately NOT idempotent - Vehicle.markInAuction()'s own
     * guard (only APPROVED -> IN_AUCTION) is preserved unchanged; a second call against an
     * already-IN_AUCTION vehicle fails closed by design. Called exclusively from
     * AuctionFinalizer.startAuction(), always after Auction.start() has already succeeded in
     * the same transaction - see that method's own Javadoc for why a second call for the same
     * auction can never reach this method in the first place, making idempotency unnecessary
     * here.
     */
    @Transactional
    public void markInAuction(Long vehicleId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new IllegalArgumentException("Автомобиль не найден: " + vehicleId));
        vehicle.markInAuction();
        vehicleRepository.save(vehicle);
        log.info("Vehicle {} marked IN_AUCTION", vehicleId);
    }

    /**
     * X2 (Authoritative Sale Completion): transitions a Vehicle from IN_AUCTION to SOLD once its
     * auction's sale has been authoritatively completed. Deliberately NOT idempotent - same
     * reasoning as markInAuction() above: Vehicle.markAsSold()'s own guard (only IN_AUCTION ->
     * SOLD) is preserved unchanged; a second call against an already-SOLD vehicle fails closed
     * by design. Called exclusively from AuctionFinalizer.completeSale(), always after
     * Auction.markSold() has already succeeded in the same transaction - see that method's own
     * Javadoc for why a second call for the same auction can never reach this method in the
     * first place, making idempotency unnecessary here.
     */
    @Transactional
    public void markAsSold(Long vehicleId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new IllegalArgumentException("Автомобиль не найден: " + vehicleId));
        vehicle.markAsSold();
        vehicleRepository.save(vehicle);
        log.info("Vehicle {} marked SOLD", vehicleId);
    }

    /**
     * X3-A (SALE_FAILED outcomes B, BUYER_FAULT): returns a vehicle to the active pool after a
     * no-fault-requiring-rediagnosis sale failure. Deliberately NOT idempotent, same reasoning
     * as markInAuction()/markAsSold() above - called exclusively from
     * SaleFailureResolutionOrchestrator.resolveSaleFailure(), whose own Auction-status guard
     * structurally prevents a second call for the same auction from ever reaching this method.
     */
    @Transactional
    public void returnToApproved(Long vehicleId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new IllegalArgumentException("Автомобиль не найден: " + vehicleId));
        vehicle.returnToApproved();
        vehicleRepository.save(vehicle);
        log.info("Vehicle {} returned to APPROVED", vehicleId);
    }

    /**
     * X3-A (SALE_FAILED outcomes PLATFORM_FAULT, C): marks a vehicle as requiring a fresh
     * diagnostic cycle after a sale failure. Deliberately NOT idempotent - same reasoning as
     * returnToApproved() above.
     */
    @Transactional
    public void markNeedsRediagnostic(Long vehicleId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new IllegalArgumentException("Автомобиль не найден: " + vehicleId));
        vehicle.markNeedsRediagnostic();
        vehicleRepository.save(vehicle);
        log.info("Vehicle {} marked NEEDS_REDIAGNOSTIC", vehicleId);
    }

    /**
     * Создать Vehicle автоматически при одобрении диагностического отчёта.
     * Данные берутся из диагностической заявки.
     */
    @Transactional
    public VehicleResponse createFromDiagnosticRequest(
            Long diagnosticRequestId,
            String brand, String model, Integer year, String vin,
            Long diagnosticUserId, String diagnosticUsername,
            Long adminId, String adminName) {

        // Если VIN уже зарегистрирован — возвращаем существующий автомобиль
        // X3-C: deliberately left untouched - does NOT update diagnosticRequestId on the
        // existing Vehicle. Updating it here would be building the re-diagnostic pointer-
        // update workflow, explicitly out of scope for X3-C (see the architecture re-lock's
        // disclosed CONCERN on this exact branch). A vehicle that reaches this branch keeps
        // whichever diagnosticRequestId it already has (possibly stale), and AuctionCommitmentResolver
        // fails closed rather than silently re-deriving a newer one.
        if (vehicleRepository.existsByVin(vin)) {
            log.warn("Vehicle with VIN {} already exists, skipping creation", vin);
            return vehicleRepository.findByVin(vin)
                    .map(VehicleResponse::fromDomain)
                    .orElseThrow(() -> new IllegalStateException("VIN conflict for: " + vin));
        }

        Vehicle vehicle = new Vehicle(brand, model, year, vin,
                com.example.autoauction.vehicle.domain.VehicleType.SEDAN, // дефолт, можно расширить
                diagnosticUserId, diagnosticUsername);
        // Сразу переводим в APPROVED (отчёт уже одобрен)
        vehicle.submitForReview();
        vehicle.approve(adminId, adminName);
        // X3-C: this IS the single, authoritative write of Vehicle.diagnosticRequestId - only
        // in this create branch, never in the VIN-dedup branch above (which deliberately
        // returns the existing Vehicle untouched - see that branch's own comment and the
        // X3-C architecture re-lock for why it stays that way).
        vehicle.setDiagnosticRequestId(diagnosticRequestId);

        Vehicle saved = vehicleRepository.save(vehicle);
        log.info("Vehicle created from diagnostic request {}: id={}, vin={}", diagnosticRequestId, saved.getId(), vin);
        return VehicleResponse.fromDomain(saved);
    }

    public long countByStatus(VehicleStatus status) {
        return vehicleRepository.findByStatus(status).size();
    }

    public long countAllVehicles() {
        return vehicleRepository.findAll().size();
    }

    @Transactional
    public VehicleResponse rejectVehicle(Long vehicleId, Long adminId, String adminName, String reason) {
        log.info("=== ОТКЛОНЕНИЕ АВТОМОБИЛЯ ID: {} ===", vehicleId);
        log.info("Администратор: {} (ID: {}), причина: {}", adminName, adminId, reason);

        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> {
                    log.error("Автомобиль не найден: {}", vehicleId);
                    return new IllegalArgumentException("Автомобиль не найден: " + vehicleId);
                });

        log.debug("Текущий статус: {}, диагностик: {}",
                vehicle.getStatus(), vehicle.getDiagnosticName());

        vehicle.reject(adminId, adminName, reason);
        log.info("Автомобиль отклонен, новый статус: NEEDS_FIXES");

        Vehicle updated = vehicleRepository.save(vehicle);
        log.debug("Автомобиль сохранен в БД");

        return VehicleResponse.fromDomain(updated);
    }
}