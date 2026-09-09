// auction/application/SaleFailureResolutionOrchestrator.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.sale.application.SaleResolutionService;
import com.example.autoauction.sale.domain.FaultParty;
import com.example.autoauction.sale.domain.SaleResolutionResult;
import com.example.autoauction.vehicle.application.VehicleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * X3-A (SALE_FAILED outcomes): the single authoritative operation that resolves a
 * WAITING_FOR_PAYMENT auction as a failed sale - B, BUYER_FAULT, PLATFORM_FAULT, or C - and
 * performs its immediate Auction, Vehicle, and buyer-deposit consequences atomically. A peer
 * of {@link AuctionFinalizer}, not an extension of it: AuctionFinalizer owns the successful
 * lifecycle (start/cancel/finalize/completeSale); this class owns exactly the failed-sale
 * branch and nothing else - no commission, no seller payout, no SellerDiagnosticCommitment,
 * no PolicyVersion, no re-diagnostic workflow beyond the one Vehicle status flip, no buyer
 * blocking, no FinancialRecoveryScheduler integration. All deliberately out of scope for this
 * slice (see the X3-A architecture fight).
 *
 * Mirrors AuctionFinalizer.completeSale()'s exact caller contract: the caller is responsible
 * for fetching {@code auction} via {@code auctionRepository.findByIdWithLock(auctionId)}
 * BEFORE calling resolveSaleFailure() - this method deliberately does NOT re-fetch or
 * re-lock. Safety against a repeat/concurrent resolution comes entirely from that pessimistic
 * lock plus {@link Auction#markSaleFailed()}'s own WAITING_FOR_PAYMENT-only guard, the
 * identical mechanism already proven for AuctionFinalizer.completeSale() (see
 * AuctionSaleCompletionIntegrationTest's T10/T11) - no new concurrency pattern is introduced.
 *
 * Outcome -&gt; consequence dispatch is fixed and exhaustive (locked decision, not
 * configurable):
 * <pre>
 *   B               -&gt; Vehicle APPROVED,            Deposit UNBLOCK
 *   BUYER_FAULT     -&gt; Vehicle APPROVED,            Deposit CONFISCATE_TO_PLATFORM
 *   PLATFORM_FAULT  -&gt; Vehicle NEEDS_REDIAGNOSTIC,   Deposit UNBLOCK
 *   C               -&gt; Vehicle NEEDS_REDIAGNOSTIC,   Deposit UNBLOCK
 * </pre>
 * No seller-penalty calculation, no SellerDiagnosticCommitment finalization - deferred to a
 * future slice pending the Auction&lt;-&gt;Commitment linkage decision (see the X3 discovery/
 * architecture fight).
 */
@Slf4j
@Service
public class SaleFailureResolutionOrchestrator {

    private final AuctionRepository auctionRepository;
    private final VehicleService vehicleService;
    private final SaleResolutionService saleResolutionService;
    private final DepositService depositService;

    public SaleFailureResolutionOrchestrator(AuctionRepository auctionRepository,
                                              VehicleService vehicleService,
                                              SaleResolutionService saleResolutionService,
                                              DepositService depositService) {
        this.auctionRepository = auctionRepository;
        this.vehicleService = vehicleService;
        this.saleResolutionService = saleResolutionService;
        this.depositService = depositService;
    }

    /**
     * Resolves {@code auction} (already pessimistically locked by the caller) as a failed
     * sale. Required sequence, all inside this one physical transaction: verify
     * WAITING_FOR_PAYMENT -&gt; reject SUCCESS -&gt; create the SaleResolution failure record -&gt;
     * {@code auction.markSaleFailed()} -&gt; the outcome-specific Vehicle consequence -&gt; the
     * outcome-specific Deposit consequence -&gt; save the Auction. Any failure at any step rolls
     * back everything already done in this method, including the SaleResolution insert and
     * the in-memory Auction transition - no partial SALE_FAILED state, no orphaned
     * SaleResolution, no stranded Vehicle/Deposit consequence is ever committed.
     */
    @Transactional
    public void resolveSaleFailure(Auction auction, SaleResolutionResult outcome, FaultParty faultParty,
                                    String failureReason, Long resolvedBy) {
        if (outcome == SaleResolutionResult.SUCCESS) {
            throw new IllegalArgumentException(
                    "resolveSaleFailure() не принимает SUCCESS - успешная продажа обрабатывается AuctionFinalizer.completeSale()");
        }
        if (auction.getStatus() != AuctionStatus.WAITING_FOR_PAYMENT) {
            throw new IllegalStateException(
                    String.format("Невозможно зафиксировать исход продажи аукциона %d в статусе '%s'. Только аукционы со статусом 'WAITING_FOR_PAYMENT' могут быть разрешены",
                            auction.getId(), auction.getStatus())
            );
        }

        saleResolutionService.createFailure(auction.getId(), outcome, faultParty, failureReason, resolvedBy);
        auction.markSaleFailed();

        Long vehicleId = auction.getVehicleId();
        Long winnerId = auction.getWinnerId();

        switch (outcome) {
            case B -> {
                vehicleService.returnToApproved(vehicleId);
                depositService.unblockForSaleFailure(auction.getId(), winnerId);
            }
            case BUYER_FAULT -> {
                vehicleService.returnToApproved(vehicleId);
                depositService.confiscateToPlatform(auction.getId(), winnerId);
            }
            case PLATFORM_FAULT, C -> {
                vehicleService.markNeedsRediagnostic(vehicleId);
                depositService.unblockForSaleFailure(auction.getId(), winnerId);
            }
            default -> throw new IllegalArgumentException("Неподдерживаемый outcome для resolveSaleFailure: " + outcome);
        }

        auctionRepository.save(auction);
        log.info("Auction {} resolved as SALE_FAILED: outcome={}, faultParty={}, vehicle={}, winner={}",
                auction.getId(), outcome, faultParty, vehicleId, winnerId);
    }
}
