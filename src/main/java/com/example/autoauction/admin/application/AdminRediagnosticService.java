package com.example.autoauction.admin.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest.DiagnosticRequestStatus;
import com.example.autoauction.diagnostic.domain.RediagnosticReason;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.identity.application.VerifiedIdentityGate;
import com.example.autoauction.notification.application.NotificationService;
import com.example.autoauction.sale.domain.FaultParty;
import com.example.autoauction.sale.domain.SaleResolution;
import com.example.autoauction.sale.domain.port.SaleResolutionRepository;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * MVP-005: an admin orders the re-diagnostic of a vehicle in NEEDS_REDIAGNOSTIC (approved
 * business policy). The re-diagnostic is a NEW DiagnosticRequest for the SAME vehicle, linked to
 * the request it supersedes, with a mandatory machine-readable reason; nothing about earlier
 * requests, reports or media is changed. Who pays follows the reason:
 * <ul>
 *   <li>DIAGNOSTIC_ERROR - AlSo pays: the new commitment carries a zero fee, nothing is charged.</li>
 *   <li>SELLER_FAULT - the seller pays the configured diagnostic fee, charged once now. The
 *       violation penalty was already charged when the sale was resolved as C and is not
 *       repeated.</li>
 * </ul>
 * In both cases the seller's diagnostic commitment (guarantee freeze) is created exactly as for
 * any diagnostic request, and the seller must have a verified identity (KYC).
 */
@Slf4j
@Service
public class AdminRediagnosticService {

    /** A re-diagnostic in one of these states is still running - no second one may be ordered. */
    private static final Set<DiagnosticRequestStatus> IN_PROGRESS = EnumSet.of(
            DiagnosticRequestStatus.PENDING, DiagnosticRequestStatus.IN_PROGRESS, DiagnosticRequestStatus.COMPLETED);

    private final VehicleRepository vehicleRepository;
    private final DiagnosticRequestRepository requestRepository;
    private final AuctionRepository auctionRepository;
    private final SaleResolutionRepository saleResolutionRepository;
    private final SellerDiagnosticCommitmentService commitmentService;
    private final DepositService depositService;
    private final SystemSettingsService systemSettingsService;
    private final VerifiedIdentityGate verifiedIdentityGate;
    private final NotificationService notificationService;

    public AdminRediagnosticService(VehicleRepository vehicleRepository,
                                    DiagnosticRequestRepository requestRepository,
                                    AuctionRepository auctionRepository,
                                    SaleResolutionRepository saleResolutionRepository,
                                    SellerDiagnosticCommitmentService commitmentService,
                                    DepositService depositService,
                                    SystemSettingsService systemSettingsService,
                                    VerifiedIdentityGate verifiedIdentityGate,
                                    NotificationService notificationService) {
        this.vehicleRepository = vehicleRepository;
        this.requestRepository = requestRepository;
        this.auctionRepository = auctionRepository;
        this.saleResolutionRepository = saleResolutionRepository;
        this.commitmentService = commitmentService;
        this.depositService = depositService;
        this.systemSettingsService = systemSettingsService;
        this.verifiedIdentityGate = verifiedIdentityGate;
        this.notificationService = notificationService;
    }

    @Transactional
    public DiagnosticRequest orderRediagnostic(Long vehicleId, RediagnosticReason reason, String comment, Long adminId) {
        if (reason == null) {
            throw new IllegalArgumentException("Причина повторной диагностики обязательна");
        }
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new IllegalArgumentException("Автомобиль не найден: " + vehicleId));
        if (vehicle.getStatus() != VehicleStatus.NEEDS_REDIAGNOSTIC) {
            throw new IllegalStateException("Повторная диагностика возможна только для автомобиля в статусе "
                    + "NEEDS_REDIAGNOSTIC. Текущий статус: " + vehicle.getStatus());
        }
        if (vehicle.getDiagnosticRequestId() == null) {
            throw new IllegalStateException("У автомобиля " + vehicleId + " нет исходной диагностической заявки");
        }

        // The request this one supersedes: the latest re-diagnostic of the vehicle, else the
        // vehicle's current (original) request.
        DiagnosticRequest previous = requestRepository.findLatestRediagnosticOfVehicle(vehicleId)
                .orElseGet(() -> requestRepository.findById(vehicle.getDiagnosticRequestId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Диагностическая заявка " + vehicle.getDiagnosticRequestId() + " не найдена")));
        if (previous.isRediagnostic() && IN_PROGRESS.contains(previous.getStatus())) {
            throw new IllegalStateException("Повторная диагностика уже назначена (заявка #" + previous.getId() + ")");
        }
        if (requestRepository.existsByPreviousDiagnosticRequestId(previous.getId())) {
            throw new IllegalStateException("Заявка #" + previous.getId() + " уже заменена повторной диагностикой");
        }

        requireReasonMatchesSaleFault(vehicleId, reason);

        Long sellerId = previous.getUserId();
        verifiedIdentityGate.requireVerified(sellerId);

        // Settings are read before anything is written (the fee is fail-closed when unset).
        BigDecimal guarantee = systemSettingsService.getBigDecimal("diagnostic_deposit_amount", new BigDecimal("10000"));
        BigDecimal sellerFee = reason == RediagnosticReason.SELLER_FAULT
                ? systemSettingsService.getRequiredBigDecimal("diagnostic_fee_amount")
                : BigDecimal.ZERO;
        // Deposit guard before any write: the seller must cover the fee (if any) plus the usual
        // diagnostic guarantee freeze.
        BigDecimal available = depositService.getDepositByUserId(sellerId).availableBalance();
        if (available.compareTo(sellerFee.add(guarantee)) < 0) {
            throw new IllegalStateException(String.format(
                    "Недостаточно средств на депозите продавца: доступно %s, требуется %s",
                    available.toPlainString(), sellerFee.add(guarantee).toPlainString()));
        }

        OffsetDateTime now = OffsetDateTime.now();
        DiagnosticRequest next = new DiagnosticRequest();
        next.setUserId(sellerId);
        next.setBrand(previous.getBrand());
        next.setModel(previous.getModel());
        next.setYear(previous.getYear());
        next.setVin(previous.getVin());
        next.setDepositFrozen(guarantee);
        next.setStatus(DiagnosticRequestStatus.PENDING);
        next.setPreviousDiagnosticRequestId(previous.getId());
        next.setVehicleId(vehicleId);
        next.setRediagnosticReason(reason);
        next.setRediagnosticComment(comment == null || comment.isBlank() ? null : comment.trim());
        next.setRediagnosticOrderedBy(adminId);
        next.setRediagnosticOrderedAt(now);
        next.setCreatedAt(now);
        next.setUpdatedAt(now);
        DiagnosticRequest saved = requestRepository.save(next);

        depositService.chargeRediagnosticFee(sellerId, saved.getId(), sellerFee);
        // Zero fee in both cases: AlSo pays (DIAGNOSTIC_ERROR) or the seller already paid above
        // (SELLER_FAULT) - the commitment can therefore never charge the fee (again).
        commitmentService.freezeCommitment(sellerId, saved.getId(), guarantee, BigDecimal.ZERO);

        notifySeller(saved, reason, sellerFee);
        log.info("Admin {} ordered re-diagnostic {} of vehicle {} (previous request {}, reason {}, seller fee {})",
                adminId, saved.getId(), vehicleId, previous.getId(), reason, sellerFee);
        return saved;
    }

    /** MVP-005: every diagnostic request of the vehicle's VIN, oldest first. */
    @Transactional(readOnly = true)
    public List<DiagnosticRequest> diagnosticHistory(Long vehicleId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new IllegalArgumentException("Автомобиль не найден: " + vehicleId));
        return requestRepository.findByVinOrderByCreatedAtAsc(vehicle.getVin());
    }

    /**
     * The reason must agree with the fault recorded for the failed sale that sent the vehicle to
     * re-diagnostic - otherwise who pays would contradict the financial resolution already
     * applied (no penalty for PLATFORM_FAULT, penalty for C).
     */
    private void requireReasonMatchesSaleFault(Long vehicleId, RediagnosticReason reason) {
        Auction failed = auctionRepository.findFirstByVehicleIdOrderByCreatedAtDesc(vehicleId)
                .filter(a -> a.getStatus() == AuctionStatus.SALE_FAILED)
                .orElseThrow(() -> new IllegalStateException(
                        "Не найден несостоявшийся аукцион, по которому автомобиль " + vehicleId + " отправлен на повторную диагностику"));
        SaleResolution resolution = saleResolutionRepository.findByAuctionId(failed.getId())
                .orElseThrow(() -> new IllegalStateException("Не найдено решение по аукциону " + failed.getId()));
        FaultParty expected = reason == RediagnosticReason.SELLER_FAULT ? FaultParty.SELLER : FaultParty.PLATFORM;
        if (resolution.getFaultParty() != expected) {
            throw new IllegalArgumentException(String.format(
                    "Причина %s не соответствует решению по аукциону %d (виновная сторона: %s)",
                    reason, failed.getId(), resolution.getFaultParty()));
        }
    }

    private void notifySeller(DiagnosticRequest request, RediagnosticReason reason, BigDecimal sellerFee) {
        String body = reason == RediagnosticReason.SELLER_FAULT
                ? String.format("Назначена повторная диагностика автомобиля %s %s по вине продавца. "
                        + "С депозита удержана стоимость диагностики: %s сом.", request.getBrand(), request.getModel(),
                        sellerFee.toPlainString())
                : String.format("Назначена повторная диагностика автомобиля %s %s из-за ошибки диагностики. "
                        + "Её стоимость оплачивает платформа.", request.getBrand(), request.getModel());
        try {
            notificationService.send(request.getUserId(), "DIAG_REDIAGNOSTIC_ORDERED",
                    "Назначена повторная диагностика", body, request.getId(), "DIAGNOSTIC");
        } catch (Exception e) {
            log.warn("Failed to notify seller {} about re-diagnostic {}: {}", request.getUserId(), request.getId(), e.getMessage());
        }
    }
}
