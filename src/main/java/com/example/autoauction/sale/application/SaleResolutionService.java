package com.example.autoauction.sale.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.sale.domain.HandoverEvidence;
import com.example.autoauction.sale.domain.PaymentEvidence;
import com.example.autoauction.sale.domain.SaleResolution;
import com.example.autoauction.sale.domain.port.HandoverEvidenceRepository;
import com.example.autoauction.sale.domain.port.PaymentEvidenceRepository;
import com.example.autoauction.sale.domain.port.SaleResolutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * X1: the sole controlled entry point for creating a SUCCESS SaleResolution and for
 * transitioning its financialResolutionStatus.
 *
 * createSuccess deliberately takes only (auctionId, resolvedBy) - it does NOT accept
 * paymentEvidenceId/handoverEvidenceId as parameters. Both are derived internally via
 * findByAuctionId, per the locked design's own instruction to "prefer deriving IDs from
 * trusted Auction context ... instead of accepting redundant untrusted identifiers from
 * callers" - this makes an evidence row belonging to the wrong auction structurally
 * unreachable through this API, rather than merely checked for after the fact. The explicit
 * post-lookup auctionId re-check below is deliberate defense-in-depth (and is what the locked
 * "wrong-auction evidence rejected" test exercises against a substitutable repository), not a
 * sign that the derive-don't-accept principle was abandoned.
 *
 * Deliberately does NOT change Auction status - that belongs to a later slice (X2).
 */
@Slf4j
@Service
public class SaleResolutionService {

    private final SaleResolutionRepository saleResolutionRepository;
    private final PaymentEvidenceRepository paymentEvidenceRepository;
    private final HandoverEvidenceRepository handoverEvidenceRepository;
    private final AuctionRepository auctionRepository;
    private final Clock clock;

    public SaleResolutionService(SaleResolutionRepository saleResolutionRepository,
                                  PaymentEvidenceRepository paymentEvidenceRepository,
                                  HandoverEvidenceRepository handoverEvidenceRepository,
                                  AuctionRepository auctionRepository,
                                  Clock clock) {
        this.saleResolutionRepository = saleResolutionRepository;
        this.paymentEvidenceRepository = paymentEvidenceRepository;
        this.handoverEvidenceRepository = handoverEvidenceRepository;
        this.auctionRepository = auctionRepository;
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    @Transactional
    public SaleResolution createSuccess(Long auctionId, Long resolvedBy) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Аукцион не найден: " + auctionId));

        if (auction.getStatus() != AuctionStatus.WAITING_FOR_PAYMENT) {
            throw new IllegalStateException(
                    "SaleResolution можно создать только для аукциона в статусе WAITING_FOR_PAYMENT, текущий: "
                            + auction.getStatus());
        }
        if (auction.getWinnerId() == null) {
            throw new IllegalStateException("У аукциона " + auctionId + " нет победителя");
        }
        if (auction.getWinningBid() == null) {
            throw new IllegalStateException("У аукциона " + auctionId + " не установлена winningBid");
        }

        PaymentEvidence payment = paymentEvidenceRepository.findByAuctionId(auctionId)
                .orElseThrow(() -> new IllegalStateException("PaymentEvidence отсутствует для аукциона " + auctionId));
        HandoverEvidence handover = handoverEvidenceRepository.findByAuctionId(auctionId)
                .orElseThrow(() -> new IllegalStateException("HandoverEvidence отсутствует для аукциона " + auctionId));

        if (!payment.getAuctionId().equals(auctionId)) {
            throw new IllegalStateException("PaymentEvidence принадлежит другому аукциону");
        }
        if (!handover.getAuctionId().equals(auctionId)) {
            throw new IllegalStateException("HandoverEvidence принадлежит другому аукциону");
        }
        if (!payment.getBuyerId().equals(auction.getWinnerId())) {
            throw new IllegalStateException("PaymentEvidence.buyerId не совпадает с победителем аукциона");
        }
        if (!handover.getBuyerId().equals(auction.getWinnerId())) {
            throw new IllegalStateException("HandoverEvidence.buyerId не совпадает с победителем аукциона");
        }
        if (!handover.getVehicleId().equals(auction.getVehicleId())) {
            throw new IllegalStateException("HandoverEvidence.vehicleId не совпадает с vehicleId аукциона");
        }
        if (payment.getAmount().compareTo(auction.getWinningBid()) != 0) {
            throw new IllegalStateException("PaymentEvidence.amount не совпадает с winningBid аукциона");
        }
        if (saleResolutionRepository.existsByAuctionId(auctionId)) {
            throw new IllegalStateException("SaleResolution уже существует для аукциона " + auctionId);
        }

        SaleResolution resolution = new SaleResolution(auctionId, resolvedBy, now(),
                payment.getId(), handover.getId(), now());
        try {
            SaleResolution saved = saleResolutionRepository.save(resolution);
            log.info("SaleResolution SUCCESS created: auction={}, resolvedBy={}, paymentEvidence={}, handoverEvidence={}",
                    auctionId, resolvedBy, payment.getId(), handover.getId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("SaleResolution уже существует для аукциона " + auctionId, e);
        }
    }

    @Transactional
    public void markFinancialResolutionDone(Long saleResolutionId) {
        if (!saleResolutionRepository.claimFinancialResolutionDone(saleResolutionId)) {
            throw new IllegalStateException(
                    "Не удалось перевести SaleResolution " + saleResolutionId + " в DONE - ожидался статус PENDING");
        }
        log.info("SaleResolution {} financialResolutionStatus -> DONE", saleResolutionId);
    }

    @Transactional
    public void markFinancialResolutionFailed(Long saleResolutionId) {
        if (!saleResolutionRepository.claimFinancialResolutionFailed(saleResolutionId)) {
            throw new IllegalStateException(
                    "Не удалось перевести SaleResolution " + saleResolutionId + " в FAILED - ожидался статус PENDING");
        }
        log.info("SaleResolution {} financialResolutionStatus -> FAILED", saleResolutionId);
    }

    @Transactional
    public void resetFinancialResolutionToPending(Long saleResolutionId) {
        if (!saleResolutionRepository.resetFinancialResolutionToPending(saleResolutionId)) {
            throw new IllegalStateException(
                    "Не удалось сбросить SaleResolution " + saleResolutionId + " в PENDING - ожидался статус FAILED");
        }
        log.info("SaleResolution {} financialResolutionStatus -> PENDING (manual reset)", saleResolutionId);
    }
}
