// auction/application/AuctionCommitmentResolver.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * X3-C: resolves and validates the single eligible SellerDiagnosticCommitment for a NEW
 * Auction. This is the narrow collaborator the X3-C architecture re-lock calls for - its one
 * responsibility is the deterministic chain:
 *
 * <pre>
 * Vehicle.diagnosticRequestId -> DiagnosticRequest -> SellerDiagnosticCommitment
 * </pre>
 *
 * No VIN matching, no "latest commitment"/"latest diagnostic request" heuristic, no admin-
 * supplied commitment id - the vehicle's own stored pointer is the only input this resolver
 * ever reads. A stale pointer (e.g. the VehicleService.createFromDiagnosticRequest VIN-dedup
 * branch never updating it - see that method's own comment) fails closed here rather than
 * falling back to any other lookup.
 *
 * This resolver performs no write of any kind: it does not create an Auction, does not
 * mutate Vehicle, does not change SellerDiagnosticCommitment status, and has no Deposit/
 * ledger side effect. It must be invoked inside the same transaction as the Auction it backs
 * (see AdminAuctionService.createAuction) so that a failure here - or a later failure to
 * persist the Auction - leaves no partial linkage of any kind.
 */
@Slf4j
@Component
public class AuctionCommitmentResolver {

    private final VehicleRepository vehicleRepository;
    private final DiagnosticRequestRepository diagnosticRequestRepository;
    private final SellerDiagnosticCommitmentRepository commitmentRepository;
    private final AuctionRepository auctionRepository;

    public AuctionCommitmentResolver(VehicleRepository vehicleRepository,
                                      DiagnosticRequestRepository diagnosticRequestRepository,
                                      SellerDiagnosticCommitmentRepository commitmentRepository,
                                      AuctionRepository auctionRepository) {
        this.vehicleRepository = vehicleRepository;
        this.diagnosticRequestRepository = diagnosticRequestRepository;
        this.commitmentRepository = commitmentRepository;
        this.auctionRepository = auctionRepository;
    }

    /**
     * Resolves the id of the single eligible SellerDiagnosticCommitment for a new Auction on
     * the given vehicle, or throws. Never returns null - a commitment-less new Auction is not
     * a representable outcome of this method; the caller must not catch the exceptions below
     * and fall back to a null commitment id.
     *
     * @throws IllegalArgumentException if the vehicle, its diagnostic-cycle pointer, or the
     *         commitment for that cycle cannot be found - "missing required data" rejections.
     * @throws IllegalStateException if the data found is inconsistent (a dangling
     *         diagnosticRequestId pointing at no row) or the commitment exists but is not in
     *         an eligible state for a new Auction (wrong status, or already linked to another
     *         Auction) - "found, but in the wrong state" rejections, matching the exception
     *         family Auction's own domain guards (markSold, markSaleFailed, ...) already use.
     */
    public Long resolveEligibleCommitment(Long vehicleId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new IllegalArgumentException("Автомобиль с ID " + vehicleId + " не найден"));

        Long diagnosticRequestId = vehicle.getDiagnosticRequestId();
        if (diagnosticRequestId == null) {
            log.debug("Vehicle {} has no diagnosticRequestId on record - no commitment resolvable", vehicleId);
            throw new IllegalArgumentException(
                    "У автомобиля " + vehicleId + " нет диагностического цикла на записи. " +
                    "Новый аукцион требует eligible seller diagnostic commitment.");
        }

        DiagnosticRequest diagnosticRequest = diagnosticRequestRepository.findById(diagnosticRequestId)
                .orElseThrow(() -> new IllegalStateException(
                        "Диагностическая заявка " + diagnosticRequestId + " (указана у автомобиля " +
                        vehicleId + ") не найдена - повреждённые данные"));

        SellerDiagnosticCommitment commitment = commitmentRepository.findByDiagnosticRequestId(diagnosticRequest.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Для диагностической заявки " + diagnosticRequestId + " не найден seller diagnostic commitment"));

        if (commitment.getStatus() != CommitmentStatus.AWAITING_FINALIZATION) {
            throw new IllegalStateException(String.format(
                    "Commitment %d не готов для привязки к новому аукциону: статус '%s'. " +
                    "Только AWAITING_FINALIZATION допускает создание нового аукциона",
                    commitment.getId(), commitment.getStatus()));
        }

        if (auctionRepository.existsBySellerDiagnosticCommitmentId(commitment.getId())) {
            throw new IllegalStateException(
                    "Commitment " + commitment.getId() + " уже привязан к другому аукциону и не может быть переиспользован");
        }

        log.debug("Resolved commitment {} for vehicle {} via diagnosticRequest {}",
                commitment.getId(), vehicleId, diagnosticRequestId);
        return commitment.getId();
    }
}
