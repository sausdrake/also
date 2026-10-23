// auction/application/AuctionNoSaleResolutionOrchestrator.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.diagnostic.commitment.application.SellerViolationResolutionEngine;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.commitment.domain.FinalizationOutcome;
import com.example.autoauction.diagnostic.commitment.domain.TriggerSource;
import com.example.autoauction.vehicle.application.VehicleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * AUC-NOSALE-01 (approved business policy, MVP-BUSINESS-DECISIONS §3.1): the seller-side
 * consequences of an auction that ended WITHOUT a sale - EXPIRED (no bids, or reserve not met)
 * or CANCELLED. Before this class both outcomes left the seller's diagnostic guarantee in
 * AWAITING_FINALIZATION with no way out and the vehicle IN_AUCTION after EXPIRED.
 *
 * <pre>
 *   EXPIRED                          -&gt; guarantee returned in full (Outcome A), vehicle NEEDS_REDIAGNOSTIC
 *   CANCELLED, no seller violation   -&gt; guarantee returned in full (Outcome A), vehicle NEEDS_REDIAGNOSTIC
 *   CANCELLED, confirmed violation(s) -&gt; penalty withheld, remainder returned (Outcome C via the shared
 *                                       SellerViolationResolutionEngine), vehicle NEEDS_REDIAGNOSTIC
 * </pre>
 *
 * Cancellation, seller violation, penalty and guarantee release stay separate events: the
 * Auction transition is owned by {@link AuctionFinalizer}, the penalty calculation and its
 * audit trail by SellerViolationResolutionEngine, the guarantee release by
 * SaleCommitmentResolutionOrchestrator; this class only decides which of them applies.
 *
 * Exactly once: both commitment paths claim the commitment row first (conditional UPDATE on
 * AWAITING_FINALIZATION) before any ledger movement, and a repeat with the same outcome is a
 * no-op - a second finalization, a second admin request, or a concurrent repair can never move
 * money twice. Plain {@code @Transactional} (REQUIRED): joins the caller's transaction, so a
 * failure here rolls back the auction transition as well.
 */
@Slf4j
@Service
public class AuctionNoSaleResolutionOrchestrator {

    private final VehicleService vehicleService;
    private final SaleCommitmentResolutionOrchestrator saleCommitmentResolutionOrchestrator;
    private final SellerViolationResolutionEngine sellerViolationResolutionEngine;
    private final SellerDiagnosticCommitmentRepository commitmentRepository;

    public AuctionNoSaleResolutionOrchestrator(VehicleService vehicleService,
                                               SaleCommitmentResolutionOrchestrator saleCommitmentResolutionOrchestrator,
                                               SellerViolationResolutionEngine sellerViolationResolutionEngine,
                                               SellerDiagnosticCommitmentRepository commitmentRepository) {
        this.vehicleService = vehicleService;
        this.saleCommitmentResolutionOrchestrator = saleCommitmentResolutionOrchestrator;
        this.sellerViolationResolutionEngine = sellerViolationResolutionEngine;
        this.commitmentRepository = commitmentRepository;
    }

    /**
     * Repair path for an auction that ended without a sale before these consequences existed:
     * the guarantee is returned in full only while it is still open (AWAITING_FINALIZATION); a
     * guarantee already resolved in any way (A, or C after a cancellation for a violation) is
     * left exactly as it is. The vehicle transition is idempotent as well.
     */
    @Transactional
    public void repair(Auction auction, Long resolvedBy) {
        Long commitmentId = auction.getSellerDiagnosticCommitmentId();
        boolean resolved = commitmentId != null && commitmentRepository.findById(commitmentId)
                .map(c -> c.getStatus() == CommitmentStatus.FINALIZED)
                .orElse(false);
        if (resolved) {
            vehicleService.markNeedsRediagnosticAfterNoSale(auction.getVehicleId());
            log.info("Auction {}: seller commitment {} already resolved - only the vehicle is checked", auction.getId(), commitmentId);
            return;
        }
        resolve(auction, List.of(), resolvedBy);
    }

    /**
     * Applies the no-sale consequences to {@code auction}, which must already be EXPIRED or
     * CANCELLED (in memory or persisted) and locked by the caller. A non-empty
     * {@code violationDefinitionIds} is only accepted for CANCELLED.
     */
    @Transactional
    public void resolve(Auction auction, List<Long> violationDefinitionIds, Long resolvedBy) {
        boolean violations = violationDefinitionIds != null && !violationDefinitionIds.isEmpty();
        if (auction.getStatus() != AuctionStatus.EXPIRED && auction.getStatus() != AuctionStatus.CANCELLED) {
            throw new IllegalStateException(String.format(
                    "Аукцион %d в статусе '%s' не является аукционом без продажи", auction.getId(), auction.getStatus()));
        }
        if (violations && auction.getStatus() != AuctionStatus.CANCELLED) {
            throw new IllegalArgumentException("Нарушения продавца при завершении без продажи указываются только при отмене аукциона");
        }

        vehicleService.markNeedsRediagnosticAfterNoSale(auction.getVehicleId());

        Long commitmentId = auction.getSellerDiagnosticCommitmentId();
        if (commitmentId == null) {
            if (violations) {
                throw new IllegalStateException("У аукциона " + auction.getId()
                        + " нет гарантии продавца - удержать штраф не из чего");
            }
            log.info("Auction {} ended without a sale and has no seller commitment (historical) - nothing to release",
                    auction.getId());
            return;
        }
        if (violations) {
            sellerViolationResolutionEngine.resolve(commitmentId, auction.getPolicyVersionId(), violationDefinitionIds,
                    resolvedBy, TriggerSource.AUCTION_CANCELLED_C, auction.getId());
        } else {
            saleCommitmentResolutionOrchestrator.resolve(commitmentId, FinalizationOutcome.A);
        }
        log.info("Auction {} ({}) without a sale: seller commitment {} resolved ({})", auction.getId(),
                auction.getStatus(), commitmentId, violations ? "violation" : "full return");
    }
}
