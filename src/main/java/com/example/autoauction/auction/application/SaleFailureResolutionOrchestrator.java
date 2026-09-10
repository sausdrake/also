// auction/application/SaleFailureResolutionOrchestrator.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.commitment.application.SellerViolationResolutionEngine;
import com.example.autoauction.diagnostic.commitment.domain.FinalizationOutcome;
import com.example.autoauction.diagnostic.commitment.domain.TriggerSource;
import com.example.autoauction.sale.application.PaymentRefundService;
import com.example.autoauction.sale.application.SaleResolutionService;
import com.example.autoauction.sale.domain.FaultParty;
import com.example.autoauction.sale.domain.SaleResolution;
import com.example.autoauction.sale.domain.SaleResolutionResult;
import com.example.autoauction.sale.domain.port.PaymentEvidenceRepository;
import com.example.autoauction.vehicle.application.VehicleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * X3-A (SALE_FAILED outcomes) / X3-D (seller commitment consequence): the single authoritative
 * operation that resolves a WAITING_FOR_PAYMENT auction as a failed sale - B, BUYER_FAULT,
 * PLATFORM_FAULT, or C - and performs its immediate Auction, Vehicle, buyer-deposit, and
 * (X3-D, for B/BUYER_FAULT/PLATFORM_FAULT only) seller-commitment consequences atomically. A
 * peer of {@link AuctionFinalizer}, not an extension of it: AuctionFinalizer owns the
 * successful lifecycle (start/cancel/finalize/completeSale); this class owns exactly the
 * failed-sale branch and nothing else - no commission, no seller payout, no PolicyVersion, no
 * re-diagnostic workflow beyond the one Vehicle status flip, no buyer blocking, no
 * FinancialRecoveryScheduler integration. All deliberately out of scope for this slice.
 *
 * X3-D deliberately does NOT extend to Outcome C - no seller-violation policy exists yet (see
 * the X3-D architecture fight). C's existing Vehicle/Deposit consequences (unchanged since
 * X3-A) still run; only the commitment/financialResolutionStatus consequence is skipped for C.
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
    private final SaleCommitmentResolutionOrchestrator saleCommitmentResolutionOrchestrator;
    private final SellerViolationResolutionEngine sellerViolationResolutionEngine;
    private final PaymentEvidenceRepository paymentEvidenceRepository;
    private final PaymentRefundService paymentRefundService;

    public SaleFailureResolutionOrchestrator(AuctionRepository auctionRepository,
                                              VehicleService vehicleService,
                                              SaleResolutionService saleResolutionService,
                                              DepositService depositService,
                                              SaleCommitmentResolutionOrchestrator saleCommitmentResolutionOrchestrator,
                                              SellerViolationResolutionEngine sellerViolationResolutionEngine,
                                              PaymentEvidenceRepository paymentEvidenceRepository,
                                              PaymentRefundService paymentRefundService) {
        this.auctionRepository = auctionRepository;
        this.vehicleService = vehicleService;
        this.saleResolutionService = saleResolutionService;
        this.depositService = depositService;
        this.saleCommitmentResolutionOrchestrator = saleCommitmentResolutionOrchestrator;
        this.sellerViolationResolutionEngine = sellerViolationResolutionEngine;
        this.paymentEvidenceRepository = paymentEvidenceRepository;
        this.paymentRefundService = paymentRefundService;
    }

    /**
     * Resolves {@code auction} (already pessimistically locked by the caller) as a failed
     * sale. Required sequence, all inside this one physical transaction: verify
     * WAITING_FOR_PAYMENT -&gt; reject SUCCESS -&gt; (Slice 1) if PaymentEvidence exists, create the
     * PaymentRefundEvidence -&gt; create the SaleResolution failure record -&gt;
     * {@code auction.markSaleFailed()} -&gt; the outcome-specific Vehicle consequence -&gt; the
     * outcome-specific Deposit consequence -&gt; save the Auction. Any failure at any step rolls
     * back everything already done in this method, including the PaymentRefundEvidence and
     * SaleResolution inserts and the in-memory Auction transition - no partial SALE_FAILED
     * state, no orphaned refund/SaleResolution, no stranded Vehicle/Deposit consequence is
     * ever committed.
     */
    @Transactional
    public SaleResolution resolveSaleFailure(Auction auction, SaleResolutionResult outcome, FaultParty faultParty,
                                    String failureReason, Long resolvedBy, List<Long> violationDefinitionIds) {
        if (outcome == SaleResolutionResult.SUCCESS) {
            throw new IllegalArgumentException(
                    "resolveSaleFailure() не принимает SUCCESS - успешная продажа обрабатывается AuctionFinalizer.completeSale()");
        }
        // X3-E: violationDefinitionIds is exclusively a C concept - any non-empty value for
        // B/BUYER_FAULT/PLATFORM_FAULT is a caller error, rejected before touching anything.
        if (outcome != SaleResolutionResult.C && violationDefinitionIds != null && !violationDefinitionIds.isEmpty()) {
            throw new IllegalArgumentException("violationDefinitionIds допустим только для outcome=C");
        }
        if (auction.getStatus() != AuctionStatus.WAITING_FOR_PAYMENT) {
            throw new IllegalStateException(
                    String.format("Невозможно зафиксировать исход продажи аукциона %d в статусе '%s'. Только аукционы со статусом 'WAITING_FOR_PAYMENT' могут быть разрешены",
                            auction.getId(), auction.getStatus())
            );
        }

        // Slice 1 (Sale Completion & Failure API): locked winning-price disposition. If the buyer
        // has already physically paid (PaymentEvidence exists), the full winning price is returned
        // to the buyer on EVERY failure outcome (B/BUYER_FAULT/PLATFORM_FAULT/C) - recorded as an
        // immutable PaymentRefundEvidence in THIS same physical transaction, before the failure is
        // finalized. PaymentRefundService owns evidence construction (amount/buyer/ids derived
        // from the loaded PaymentEvidence, never supplied); this orchestrator only decides
        // whether the refund is due. If no PaymentEvidence exists, there is nothing to refund and
        // the existing failure behaviour is unchanged.
        if (paymentEvidenceRepository.existsByAuctionId(auction.getId())) {
            paymentRefundService.recordRefund(auction.getId(), resolvedBy);
        }

        SaleResolution resolution = saleResolutionService.createFailure(
                auction.getId(), outcome, faultParty, failureReason, resolvedBy);
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

        // X3-D/X3-E: seller commitment consequence + financialResolutionStatus, for every
        // outcome this method supports - B/BUYER_FAULT/PLATFORM_FAULT resolve via the existing
        // SaleCommitmentResolutionOrchestrator (A/B only); C (X3-E) resolves via the shared
        // SellerViolationResolutionEngine, using Auction.policyVersionId as its policy
        // snapshot (locked X3-E business decision - the post-auction trigger's own reference,
        // deliberately independent from SellerDiagnosticCommitment.policyVersionId). Uses
        // ONLY auction.getSellerDiagnosticCommitmentId() - never Vehicle/DiagnosticRequest/
        // VIN/latest-commitment re-resolution.
        if (auction.getSellerDiagnosticCommitmentId() != null) {
            if (outcome == SaleResolutionResult.C) {
                if (violationDefinitionIds == null || violationDefinitionIds.isEmpty()) {
                    throw new IllegalArgumentException(
                            "violationDefinitionIds обязателен для Outcome C, когда у аукциона есть seller commitment");
                }
                sellerViolationResolutionEngine.resolve(auction.getSellerDiagnosticCommitmentId(),
                        auction.getPolicyVersionId(), violationDefinitionIds, resolvedBy,
                        TriggerSource.SALE_FAILED_C, auction.getId());
            } else {
                FinalizationOutcome commitmentOutcome = (outcome == SaleResolutionResult.B)
                        ? FinalizationOutcome.B
                        : FinalizationOutcome.A;
                saleCommitmentResolutionOrchestrator.resolve(
                        auction.getSellerDiagnosticCommitmentId(), commitmentOutcome);
            }
        }

        auctionRepository.save(auction);

        saleResolutionService.markFinancialResolutionDone(resolution.getId());

        log.info("Auction {} resolved as SALE_FAILED: outcome={}, faultParty={}, vehicle={}, winner={}",
                auction.getId(), outcome, faultParty, vehicleId, winnerId);
        return resolution;
    }
}
