package com.example.autoauction.sale.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.sale.domain.HandoverEvidence;
import com.example.autoauction.sale.domain.port.HandoverEvidenceRepository;
import com.example.autoauction.sale.domain.port.PaymentEvidenceRepository;
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
 *
 * Slice 1 (Sale Completion &amp; Failure API) corrections:
 * <ul>
 *   <li>The Auction is fetched with a pessimistic write lock ({@code findByIdWithLock}) inside
 *       this same {@code @Transactional} method - same cross-endpoint-consistency reasoning as
 *       {@link PaymentEvidenceService#recordPayment}.</li>
 *   <li>{@code vehicleId}/{@code buyerId} are no longer caller-supplied - both are derived from
 *       the Auction. The client supplies neither.</li>
 *   <li>Payment-before-handover is an invariant of this creation use case itself: a
 *       {@link com.example.autoauction.sale.domain.PaymentEvidence} for the same auction MUST
 *       already exist. Enforced here, not only at the controller.</li>
 * </ul>
 */
@Slf4j
@Service
public class HandoverEvidenceService {

    private final HandoverEvidenceRepository handoverEvidenceRepository;
    private final PaymentEvidenceRepository paymentEvidenceRepository;
    private final AuctionRepository auctionRepository;
    private final Clock clock;

    public HandoverEvidenceService(HandoverEvidenceRepository handoverEvidenceRepository,
                                    PaymentEvidenceRepository paymentEvidenceRepository,
                                    AuctionRepository auctionRepository,
                                    Clock clock) {
        this.handoverEvidenceRepository = handoverEvidenceRepository;
        this.paymentEvidenceRepository = paymentEvidenceRepository;
        this.auctionRepository = auctionRepository;
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    @Transactional
    public HandoverEvidence recordHandover(Long auctionId, Long confirmedByAdminId) {
        Auction auction = auctionRepository.findByIdWithLock(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Аукцион не найден: " + auctionId));

        if (auction.getStatus() != AuctionStatus.WAITING_FOR_PAYMENT) {
            throw new IllegalStateException(
                    "HandoverEvidence можно создать только для аукциона в статусе WAITING_FOR_PAYMENT, текущий: "
                            + auction.getStatus());
        }
        if (auction.getWinnerId() == null) {
            throw new IllegalStateException("У аукциона " + auctionId + " нет победителя");
        }
        Long buyerId = auction.getWinnerId();
        Long vehicleId = auction.getVehicleId();
        if (!paymentEvidenceRepository.existsByAuctionId(auctionId)) {
            throw new IllegalStateException(
                    "HandoverEvidence нельзя создать без PaymentEvidence для аукциона " + auctionId
                            + " (оплата должна предшествовать передаче)");
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
