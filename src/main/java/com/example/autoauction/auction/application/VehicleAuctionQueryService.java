package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.dto.AuctionResponse;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Sale-UI (seller/vehicle-owner visibility): resolves the auction currently (or most recently)
 * associated with a Vehicle, for the vehicle's owner or an admin. Deliberately lives in the
 * {@code auction} module, not {@code vehicle} or {@code diagnostic} - this codebase's existing
 * dependency direction is {@code auction -> vehicle} and {@code auction -> diagnostic} (never
 * the reverse, and {@code diagnostic} does not depend on {@code auction} either), so a service
 * needing both a Vehicle lookup and a DiagnosticRequest-based ownership check can only live here
 * without introducing a new cross-module dependency or a cycle.
 *
 * <p>Ownership is resolved transitively (Vehicle -&gt; diagnosticRequestId -&gt;
 * DiagnosticRequest.userId), exactly mirroring {@code DiagnosticRequestController
 * .getRequest}'s existing owner-or-admin check - no {@code Vehicle.ownerId} field is introduced.
 * "Which auction" for a vehicle reuses the existing, previously-uncalled
 * {@code AuctionRepository.findFirstByVehicleIdOrderByCreatedAtDesc} - the most recent auction,
 * which is the correct answer both for a vehicle with exactly one auction (the common case) and
 * for a vehicle that was returned to APPROVED/NEEDS_REDIAGNOSTIC and later re-auctioned.
 */
@Service
public class VehicleAuctionQueryService {

    private final VehicleRepository vehicleRepository;
    private final DiagnosticRequestRepository diagnosticRequestRepository;
    private final AuctionRepository auctionRepository;
    private final PolicyVersionService policyVersionService;

    public VehicleAuctionQueryService(VehicleRepository vehicleRepository,
                                       DiagnosticRequestRepository diagnosticRequestRepository,
                                       AuctionRepository auctionRepository,
                                       PolicyVersionService policyVersionService) {
        this.vehicleRepository = vehicleRepository;
        this.diagnosticRequestRepository = diagnosticRequestRepository;
        this.auctionRepository = auctionRepository;
        this.policyVersionService = policyVersionService;
    }

    @Transactional(readOnly = true)
    public AuctionResponse getAuctionForVehicle(Long vehicleId, Long callerId, boolean callerIsAdmin) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new IllegalArgumentException("Автомобиль не найден: " + vehicleId));

        if (!callerIsAdmin && !isOwner(vehicle, callerId)) {
            throw new SecurityException("Нет доступа к аукциону этого автомобиля");
        }

        Auction auction = auctionRepository.findFirstByVehicleIdOrderByCreatedAtDesc(vehicleId)
                .orElseThrow(() -> new IllegalArgumentException("Аукцион для автомобиля не найден: " + vehicleId));

        return AuctionResponse.fromDomain(auction, resolveCommissionRate(auction));
    }

    private boolean isOwner(Vehicle vehicle, Long callerId) {
        Long diagnosticRequestId = vehicle.getDiagnosticRequestId();
        if (diagnosticRequestId == null) {
            return false;
        }
        return diagnosticRequestRepository.findById(diagnosticRequestId)
                .map(DiagnosticRequest::getUserId)
                .map(callerId::equals)
                .orElse(false);
    }

    /** Mirrors UserAuctionController's own resolveCommissionRate helper exactly. */
    private BigDecimal resolveCommissionRate(Auction auction) {
        if (auction.getPolicyVersionId() == null) {
            return null;
        }
        return policyVersionService.getById(auction.getPolicyVersionId()).getCommissionRate();
    }
}
