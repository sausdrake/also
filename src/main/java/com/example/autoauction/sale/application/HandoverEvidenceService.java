package com.example.autoauction.sale.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.sale.domain.HandoverEvidence;
import com.example.autoauction.sale.domain.port.HandoverEvidenceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * X1: the sole controlled entry point for recording that a vehicle and its documents were
 * handed over to the auction's winning buyer. Same cross-module dependency reasoning as
 * PaymentEvidenceService - depends on AuctionRepository directly, not VehicleService/
 * VehicleRepository, because the only fact needed from Vehicle (its id) already lives on
 * Auction itself (auction.getVehicleId()) - no Vehicle-side dependency is required at all.
 *
 * Deliberately does NOT: mark Auction SOLD, mark Vehicle SOLD, release deposit, finalize
 * commitment, calculate commission, or create a SaleResolution.
 */
@Slf4j
@Service
public class HandoverEvidenceService {

    private final HandoverEvidenceRepository handoverEvidenceRepository;
    private final AuctionRepository auctionRepository;
    private final Clock clock;

    public HandoverEvidenceService(HandoverEvidenceRepository handoverEvidenceRepository,
                                    AuctionRepository auctionRepository,
                                    Clock clock) {
        this.handoverEvidenceRepository = handoverEvidenceRepository;
        this.auctionRepository = auctionRepository;
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    @Transactional
    public HandoverEvidence recordHandover(Long auctionId, Long vehicleId, Long buyerId, Long confirmedByAdminId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Аукцион не найден: " + auctionId));

        if (auction.getStatus() != AuctionStatus.WAITING_FOR_PAYMENT) {
            throw new IllegalStateException(
                    "HandoverEvidence можно создать только для аукциона в статусе WAITING_FOR_PAYMENT, текущий: "
                            + auction.getStatus());
        }
        if (auction.getWinnerId() == null) {
            throw new IllegalStateException("У аукциона " + auctionId + " нет победителя");
        }
        if (!auction.getWinnerId().equals(buyerId)) {
            throw new IllegalArgumentException(
                    "buyerId (" + buyerId + ") не совпадает с победителем аукциона (" + auction.getWinnerId() + ")");
        }
        if (!auction.getVehicleId().equals(vehicleId)) {
            throw new IllegalArgumentException(
                    "vehicleId (" + vehicleId + ") не совпадает с vehicleId аукциона (" + auction.getVehicleId() + ")");
        }
        if (handoverEvidenceRepository.existsByAuctionId(auctionId)) {
            throw new IllegalStateException("HandoverEvidence уже существует для аукциона " + auctionId);
        }

        HandoverEvidence evidence = new HandoverEvidence(auctionId, vehicleId, buyerId, confirmedByAdminId, now(), now());
        try {
            HandoverEvidence saved = handoverEvidenceRepository.save(evidence);
            log.info("HandoverEvidence recorded: auction={}, vehicle={}, buyer={}, confirmedBy={}",
                    auctionId, vehicleId, buyerId, confirmedByAdminId);
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("HandoverEvidence уже существует для аукциона " + auctionId, e);
        }
    }
}
