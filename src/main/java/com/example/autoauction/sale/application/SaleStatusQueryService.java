package com.example.autoauction.sale.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.sale.domain.port.HandoverEvidenceRepository;
import com.example.autoauction.sale.domain.port.PaymentEvidenceRepository;
import com.example.autoauction.sale.domain.port.SaleResolutionRepository;
import com.example.autoauction.sale.web.dto.HandoverEvidenceResponse;
import com.example.autoauction.sale.web.dto.PaymentEvidenceResponse;
import com.example.autoauction.sale.web.dto.SaleResolutionResponse;
import com.example.autoauction.sale.web.dto.SaleStatusResponse;
import com.example.autoauction.sale.web.dto.SettlementCorrectionResponse;
import com.example.autoauction.sale.web.dto.SettlementResponse;
import com.example.autoauction.settlement.application.SettlementService;
import com.example.autoauction.settlement.domain.Settlement;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Read-only admin assembly of an auction's current sale-workflow state, wiring together the
 * three already-existing {@code findByAuctionId} lookups (PaymentEvidence, HandoverEvidence,
 * SaleResolution) plus the Auction's own payment-deadline fact - mirrors
 * {@code AdminReconciliationService}'s own multi-repository read-assembly shape for an admin
 * detail view. Deliberately a separate, small service rather than a method on
 * {@link SaleAdministrationService}, whose own class Javadoc explicitly disclaims being a facade
 * over the evidence services; this class owns no mutation and no financial logic at all.
 *
 * <p>Step 2C read-model extension: Settlement/SettlementCorrection are read through
 * {@link SettlementService}'s own pure pass-through methods (never its repository port directly,
 * respecting the module boundary) and folded into this same read-only assembly - never a second
 * fetch, never a recalculation of any settlement value.
 */
@Service
public class SaleStatusQueryService {

    private final AuctionRepository auctionRepository;
    private final PaymentEvidenceRepository paymentEvidenceRepository;
    private final HandoverEvidenceRepository handoverEvidenceRepository;
    private final SaleResolutionRepository saleResolutionRepository;
    private final SettlementService settlementService;
    private final Clock clock;

    public SaleStatusQueryService(AuctionRepository auctionRepository,
                                   PaymentEvidenceRepository paymentEvidenceRepository,
                                   HandoverEvidenceRepository handoverEvidenceRepository,
                                   SaleResolutionRepository saleResolutionRepository,
                                   SettlementService settlementService,
                                   Clock clock) {
        this.auctionRepository = auctionRepository;
        this.paymentEvidenceRepository = paymentEvidenceRepository;
        this.handoverEvidenceRepository = handoverEvidenceRepository;
        this.saleResolutionRepository = saleResolutionRepository;
        this.settlementService = settlementService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SaleStatusResponse getSaleStatus(Long auctionId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Аукцион не найден: " + auctionId));

        OffsetDateTime deadline = auction.getPaymentDeadline();
        boolean overdue = deadline != null && OffsetDateTime.now(clock).isAfter(deadline);

        PaymentEvidenceResponse payment = paymentEvidenceRepository.findByAuctionId(auctionId)
                .map(PaymentEvidenceResponse::fromDomain).orElse(null);
        HandoverEvidenceResponse handover = handoverEvidenceRepository.findByAuctionId(auctionId)
                .map(HandoverEvidenceResponse::fromDomain).orElse(null);
        SaleResolutionResponse resolution = saleResolutionRepository.findByAuctionId(auctionId)
                .map(SaleResolutionResponse::fromDomain).orElse(null);

        Optional<Settlement> settlementDomain = settlementService.findByAuctionId(auctionId);
        SettlementResponse settlement = settlementDomain.map(SettlementResponse::fromDomain).orElse(null);
        List<SettlementCorrectionResponse> settlementCorrections = settlementDomain
                .map(s -> settlementService.getCorrections(s.getId()).stream()
                        .map(SettlementCorrectionResponse::fromDomain).toList())
                .orElse(List.of());

        return new SaleStatusResponse(
                auction.getId(), auction.getStatus().name(), deadline, overdue,
                payment, handover, resolution, settlement, settlementCorrections);
    }
}
