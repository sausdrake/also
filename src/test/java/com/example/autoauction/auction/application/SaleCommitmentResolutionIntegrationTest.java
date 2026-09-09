// auction/application/SaleCommitmentResolutionIntegrationTest.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentFinalizationService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.FinalizationOutcome;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.sale.application.HandoverEvidenceService;
import com.example.autoauction.sale.application.PaymentEvidenceService;
import com.example.autoauction.sale.domain.FaultParty;
import com.example.autoauction.sale.domain.FinancialResolutionStatus;
import com.example.autoauction.sale.domain.SaleResolution;
import com.example.autoauction.sale.domain.SaleResolutionResult;
import com.example.autoauction.sale.domain.port.SaleResolutionRepository;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * X3-D: real-database, end-to-end proof that resolving an Auction's sale outcome
 * (completeSale / resolveSaleFailure) correctly finalizes the linked SellerDiagnosticCommitment
 * and drives SaleResolution.financialResolutionStatus to DONE - for a REAL commitment produced
 * by the full diagnostic-approval flow, not a hand-built fixture. Covers A, B, BUYER_FAULT,
 * PLATFORM_FAULT; historical NULL-commitment auctions; coexistence with the pre-existing
 * diagnostic-rejection Outcome B trigger; and the concurrent-finalization race.
 *
 * Deliberately NOT class-level @Transactional, matching every other real-transaction-boundary
 * integration test this session established (AuctionSaleCompletionIntegrationTest,
 * SaleFailureResolutionIntegrationTest, AuctionCommitmentLinkageIntegrationTest) - several tests
 * here need genuinely separate, already-committed transactions to observe persisted state and
 * the concurrency race.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("X3-D: Auction outcome -> SellerDiagnosticCommitment resolution (интеграционные тесты)")
class SaleCommitmentResolutionIntegrationTest {

    @Autowired
    private AuctionFinalizer auctionFinalizer;
    @Autowired
    private SaleFailureResolutionOrchestrator saleFailureResolutionOrchestrator;
    @Autowired
    private SaleCommitmentResolutionOrchestrator saleCommitmentResolutionOrchestrator;
    @Autowired
    private AuctionRepository auctionRepository;
    @Autowired
    private VehicleRepository vehicleRepository;
    @Autowired
    private DiagnosticRequestService diagnosticRequestService;
    @Autowired
    private DiagnosticRequestRepository diagnosticRequestRepository;
    @Autowired
    private SellerDiagnosticCommitmentService sellerDiagnosticCommitmentService;
    @Autowired
    private SellerDiagnosticCommitmentRepository sellerDiagnosticCommitmentRepository;
    @Autowired
    private SellerDiagnosticCommitmentFinalizationService finalizationService;
    @Autowired
    private DepositService depositService;
    @Autowired
    private DepositRepository depositRepository;
    @Autowired
    private PaymentEvidenceService paymentEvidenceService;
    @Autowired
    private HandoverEvidenceService handoverEvidenceService;
    @Autowired
    private SaleResolutionRepository saleResolutionRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return tx;
    }

    private record Setup(Long vehicleId, Long commitmentId, Long requestId, Long sellerId) {}

    /**
     * Full, real diagnostic-approval flow (mirrors AuctionCommitmentLinkageIntegrationTest's
     * own setup exactly) - produces a real Vehicle (APPROVED, diagnosticRequestId set) and a
     * real SellerDiagnosticCommitment (AWAITING_FINALIZATION).
     */
    private Setup approveAndGetVehicleWithCommitment(BigDecimal committedAmount, BigDecimal feeAmount) {
        Long sellerId = 900_000L + (System.nanoTime() % 100_000L);
        txTemplate().execute(status -> {
            depositService.createDeposit(sellerId, new BigDecimal("50000"));
            return null;
        });

        String vin = "X3D" + (System.nanoTime() % 100000000L);
        Long requestId = txTemplate().execute(status -> {
            DiagnosticRequest request = new DiagnosticRequest();
            request.setUserId(sellerId);
            request.setBrand("BMW");
            request.setModel("X5");
            request.setYear(2022);
            request.setVin(vin);
            request.setDepositFrozen(committedAmount);
            request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
            request.setCreatedAt(OffsetDateTime.now());
            request.setUpdatedAt(OffsetDateTime.now());
            return diagnosticRequestRepository.save(request).getId();
        });

        txTemplate().execute(status -> {
            sellerDiagnosticCommitmentService.freezeCommitment(sellerId, requestId, committedAmount, feeAmount);
            return null;
        });

        txTemplate().execute(status -> {
            DiagnosticRequest completed = diagnosticRequestRepository.findById(requestId).orElseThrow();
            completed.setStatus(DiagnosticRequest.DiagnosticRequestStatus.COMPLETED);
            diagnosticRequestRepository.save(completed);
            return null;
        });

        txTemplate().execute(status -> {
            diagnosticRequestService.approveReport(requestId, 999L);
            return null;
        });

        // Mirrors the real production lifecycle: an APPROVED vehicle moves to IN_AUCTION when
        // an Auction actually starts (AuctionFinalizer.startAuction) - required here since
        // markAsSold/returnToApproved/markNeedsRediagnostic all guard on IN_AUCTION, not
        // APPROVED.
        txTemplate().execute(status -> {
            Vehicle v = vehicleRepository.findByVin(vin).orElseThrow();
            v.markInAuction();
            vehicleRepository.save(v);
            return null;
        });

        Vehicle vehicle = txTemplate().execute(status -> vehicleRepository.findByVin(vin).orElseThrow());
        SellerDiagnosticCommitment commitment = txTemplate().execute(status ->
                sellerDiagnosticCommitmentRepository.findByDiagnosticRequestId(requestId).orElseThrow());

        return new Setup(vehicle.getId(), commitment.getId(), requestId, sellerId);
    }

    private Long createWaitingForPaymentAuctionWithCommitment(Long vehicleId, Long commitmentId,
                                                                Long winnerId, BigDecimal winningBid) {
        return txTemplate().execute(status -> {
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = new Auction(vehicleId, new BigDecimal("1000000"), null, null,
                    new BigDecimal("10000"), now.minusDays(7), now.minusHours(1), 10L);
            auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
            auction.setWinnerId(winnerId);
            auction.setWinningBid(winningBid);
            if (commitmentId != null) {
                auction.setSellerDiagnosticCommitmentId(commitmentId);
            }
            return auctionRepository.save(auction).getId();
        });
    }

    private void freezeAndMarkWinner(Long winnerId, Long auctionId, BigDecimal amount) {
        try {
            txTemplate().execute(status -> {
                depositService.createDeposit(winnerId, new BigDecimal("1000000"));
                return null;
            });
        } catch (Exception e) {
            // already exists - fine, unique winnerId per test avoids real collisions anyway.
        }
        txTemplate().execute(status -> {
            depositService.freezeForAuction(winnerId, auctionId, amount);
            return null;
        });
        txTemplate().execute(status -> {
            depositService.markAsWinner(winnerId, auctionId);
            return null;
        });
    }

    private long uniqueWinnerId() {
        return 800_000L + (System.nanoTime() % 100_000L);
    }

    // ==================== Outcome A ====================

    @Test
    @DisplayName("A: completeSale() финализирует commitment как A (fee=0,penalty=0,full refund), releaseWinnerDeposit, financialResolutionStatus=DONE")
    void completeSale_WithCommitment_FinalizesAsAAndMarksDone() {
        Setup setup = approveAndGetVehicleWithCommitment(new BigDecimal("10000"), new BigDecimal("4000"));
        long winnerId = uniqueWinnerId();
        BigDecimal winningBid = new BigDecimal("1020000.00");
        Long auctionId = createWaitingForPaymentAuctionWithCommitment(setup.vehicleId(), setup.commitmentId(), winnerId, winningBid);
        freezeAndMarkWinner(winnerId, auctionId, new BigDecimal("50000"));
        txTemplate().execute(status -> paymentEvidenceService.recordPayment(auctionId, winnerId, winningBid, 1L));
        txTemplate().execute(status -> handoverEvidenceService.recordHandover(auctionId, setup.vehicleId(), winnerId, 1L));

        txTemplate().execute(status -> {
            Auction locked = auctionRepository.findByIdWithLock(auctionId).orElseThrow();
            auctionFinalizer.completeSale(locked, 1L);
            return null;
        });

        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow();
        assertThat(commitment.getStatus()).isEqualTo(CommitmentStatus.FINALIZED);
        assertThat(commitment.getFinalizationOutcome()).isEqualTo(FinalizationOutcome.A);
        assertThat(commitment.getPenaltyAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(commitment.getRefundAmount()).isEqualByComparingTo("10000");

        SaleResolution resolution = saleResolutionRepository.findByAuctionId(auctionId).orElseThrow();
        assertThat(resolution.getFinancialResolutionStatus()).isEqualTo(FinancialResolutionStatus.DONE);

        Deposit winnerDeposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(winnerDeposit.getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ==================== Outcome B ====================

    @Test
    @DisplayName("B (Auction-triggered): commitment finalизируется как B (fee=diagnosticFee, remainder refund), не трогая diagnostic-rejection path")
    void resolveSaleFailure_OutcomeB_WithCommitment_FinalizesAsB() {
        Setup setup = approveAndGetVehicleWithCommitment(new BigDecimal("10000"), new BigDecimal("4000"));
        long winnerId = uniqueWinnerId();
        Long auctionId = createWaitingForPaymentAuctionWithCommitment(setup.vehicleId(), setup.commitmentId(), winnerId, new BigDecimal("1020000"));
        freezeAndMarkWinner(winnerId, auctionId, new BigDecimal("50000"));

        txTemplate().execute(status -> {
            Auction locked = auctionRepository.findByIdWithLock(auctionId).orElseThrow();
            saleFailureResolutionOrchestrator.resolveSaleFailure(locked, SaleResolutionResult.B, null, "no-sale", 1L);
            return null;
        });

        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow();
        assertThat(commitment.getStatus()).isEqualTo(CommitmentStatus.FINALIZED);
        assertThat(commitment.getFinalizationOutcome()).isEqualTo(FinalizationOutcome.B);
        assertThat(commitment.getPenaltyAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(commitment.getRefundAmount()).isEqualByComparingTo("6000");

        SaleResolution resolution = saleResolutionRepository.findByAuctionId(auctionId).orElseThrow();
        assertThat(resolution.getFinancialResolutionStatus()).isEqualTo(FinancialResolutionStatus.DONE);

        Vehicle vehicle = vehicleRepository.findById(setup.vehicleId()).orElseThrow();
        assertThat(vehicle.getStatus()).isEqualTo(com.example.autoauction.vehicle.domain.VehicleStatus.APPROVED);
    }

    // ==================== BUYER_FAULT ====================

    @Test
    @DisplayName("BUYER_FAULT: commitment full refund (A), buyer deposit CONFISCATED to platform, financialResolutionStatus=DONE")
    void resolveSaleFailure_BuyerFault_WithCommitment_FullRefundAndConfiscation() {
        Setup setup = approveAndGetVehicleWithCommitment(new BigDecimal("10000"), new BigDecimal("4000"));
        long winnerId = uniqueWinnerId();
        Long auctionId = createWaitingForPaymentAuctionWithCommitment(setup.vehicleId(), setup.commitmentId(), winnerId, new BigDecimal("1020000"));
        freezeAndMarkWinner(winnerId, auctionId, new BigDecimal("50000"));
        Deposit before = depositRepository.findByUserId(winnerId).orElseThrow();

        txTemplate().execute(status -> {
            Auction locked = auctionRepository.findByIdWithLock(auctionId).orElseThrow();
            saleFailureResolutionOrchestrator.resolveSaleFailure(locked, SaleResolutionResult.BUYER_FAULT, FaultParty.BUYER, "no-show", 1L);
            return null;
        });

        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow();
        assertThat(commitment.getFinalizationOutcome()).isEqualTo(FinalizationOutcome.A);
        assertThat(commitment.getRefundAmount()).isEqualByComparingTo("10000");

        Deposit after = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(after.getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(after.getBalance()).isEqualByComparingTo(before.getBalance().subtract(new BigDecimal("50000")));

        SaleResolution resolution = saleResolutionRepository.findByAuctionId(auctionId).orElseThrow();
        assertThat(resolution.getFinancialResolutionStatus()).isEqualTo(FinancialResolutionStatus.DONE);
    }

    // ==================== PLATFORM_FAULT ====================

    @Test
    @DisplayName("PLATFORM_FAULT: commitment full refund (A), buyer deposit unblocked, Vehicle NEEDS_REDIAGNOSTIC, financialResolutionStatus=DONE")
    void resolveSaleFailure_PlatformFault_WithCommitment_FullRefundAndUnblock() {
        Setup setup = approveAndGetVehicleWithCommitment(new BigDecimal("10000"), new BigDecimal("4000"));
        long winnerId = uniqueWinnerId();
        Long auctionId = createWaitingForPaymentAuctionWithCommitment(setup.vehicleId(), setup.commitmentId(), winnerId, new BigDecimal("1020000"));
        freezeAndMarkWinner(winnerId, auctionId, new BigDecimal("50000"));

        txTemplate().execute(status -> {
            Auction locked = auctionRepository.findByIdWithLock(auctionId).orElseThrow();
            saleFailureResolutionOrchestrator.resolveSaleFailure(locked, SaleResolutionResult.PLATFORM_FAULT, FaultParty.PLATFORM, "bad diagnosis", 1L);
            return null;
        });

        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow();
        assertThat(commitment.getFinalizationOutcome()).isEqualTo(FinalizationOutcome.A);
        assertThat(commitment.getRefundAmount()).isEqualByComparingTo("10000");

        Deposit winnerDeposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(winnerDeposit.getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        Vehicle vehicle = vehicleRepository.findById(setup.vehicleId()).orElseThrow();
        assertThat(vehicle.getStatus()).isEqualTo(com.example.autoauction.vehicle.domain.VehicleStatus.NEEDS_REDIAGNOSTIC);

        SaleResolution resolution = saleResolutionRepository.findByAuctionId(auctionId).orElseThrow();
        assertThat(resolution.getFinancialResolutionStatus()).isEqualTo(FinancialResolutionStatus.DONE);
    }

    // ==================== Historical NULL commitment ====================

    @Test
    @DisplayName("Historical NULL commitment: B резолвится без commitment-эффектов, financialResolutionStatus=DONE через один только buyer-deposit")
    void resolveSaleFailure_HistoricalNullCommitment_NoCommitmentSideEffectStillReachesDone() {
        // manually-created, directly-approved vehicle - no diagnosticRequestId, no commitment
        Vehicle manual = new Vehicle("Lada", "Vesta", 2022, "X3DNULL" + (System.nanoTime() % 100000L),
                com.example.autoauction.vehicle.domain.VehicleType.SEDAN, 1L, "diagnostic");
        manual.submitForReview();
        manual.approve(999L, "admin");
        manual.markInAuction();
        Long vehicleId = txTemplate().execute(status -> vehicleRepository.save(manual).getId());

        long winnerId = uniqueWinnerId();
        Long auctionId = createWaitingForPaymentAuctionWithCommitment(vehicleId, null, winnerId, new BigDecimal("1020000"));
        freezeAndMarkWinner(winnerId, auctionId, new BigDecimal("50000"));

        txTemplate().execute(status -> {
            Auction locked = auctionRepository.findByIdWithLock(auctionId).orElseThrow();
            assertThat(locked.getSellerDiagnosticCommitmentId()).isNull();
            saleFailureResolutionOrchestrator.resolveSaleFailure(locked, SaleResolutionResult.B, null, "no-sale", 1L);
            return null;
        });

        SaleResolution resolution = saleResolutionRepository.findByAuctionId(auctionId).orElseThrow();
        assertThat(resolution.getFinancialResolutionStatus()).isEqualTo(FinancialResolutionStatus.DONE);

        Deposit winnerDeposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(winnerDeposit.getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ==================== Outcome C: never touches commitment/financialResolutionStatus ====================

    @Test
    @DisplayName("C: commitment остаётся AWAITING_FINALIZATION, financialResolutionStatus остаётся PENDING (даже с реальным commitment)")
    void resolveSaleFailure_OutcomeC_LeavesCommitmentAndStatusUntouched() {
        Setup setup = approveAndGetVehicleWithCommitment(new BigDecimal("10000"), new BigDecimal("4000"));
        long winnerId = uniqueWinnerId();
        Long auctionId = createWaitingForPaymentAuctionWithCommitment(setup.vehicleId(), setup.commitmentId(), winnerId, new BigDecimal("1020000"));
        freezeAndMarkWinner(winnerId, auctionId, new BigDecimal("50000"));

        txTemplate().execute(status -> {
            Auction locked = auctionRepository.findByIdWithLock(auctionId).orElseThrow();
            saleFailureResolutionOrchestrator.resolveSaleFailure(locked, SaleResolutionResult.C, FaultParty.SELLER, "violation", 1L);
            return null;
        });

        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow();
        assertThat(commitment.getStatus()).isEqualTo(CommitmentStatus.AWAITING_FINALIZATION);

        SaleResolution resolution = saleResolutionRepository.findByAuctionId(auctionId).orElseThrow();
        assertThat(resolution.getFinancialResolutionStatus()).isEqualTo(FinancialResolutionStatus.PENDING);
    }

    // ==================== Coexistence with diagnostic-rejection trigger ====================

    @Test
    @DisplayName("Coexistence: commitment уже FINALIZED через diagnostic-rejection path -> Auction-triggered resolve(B) безопасный no-op")
    void resolve_AlreadyFinalizedViaDiagnosticPath_AuctionTriggeredResolveIsNoOp() {
        Setup setup = approveAndGetVehicleWithCommitment(new BigDecimal("10000"), new BigDecimal("4000"));
        // Finalize via the EXISTING, unmodified diagnostic-rejection path first. This commitment
        // is AWAITING_FINALIZATION via approval, not rejection, so finalizeAsOutcomeB's own
        // VEHICLE_CONDITION eligibility gate would normally reject it - this proves that gate
        // is untouched, not that this path is realistic; we only need a FINALIZED/B row to test
        // coexistence against.
        txTemplate().execute(status -> {
            DiagnosticRequest request = diagnosticRequestRepository.findById(setup.requestId()).orElseThrow();
            request.setRejectionCategory(com.example.autoauction.diagnostic.domain.DiagnosticRejectionCategory.VEHICLE_CONDITION);
            diagnosticRequestRepository.save(request);
            return null;
        });
        txTemplate().execute(status -> {
            finalizationService.finalizeAsOutcomeB(setup.requestId());
            return null;
        });

        SellerDiagnosticCommitment beforeRetry = sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow();
        assertThat(beforeRetry.getStatus()).isEqualTo(CommitmentStatus.FINALIZED);
        assertThat(beforeRetry.getFinalizationOutcome()).isEqualTo(FinalizationOutcome.B);

        // Now the Auction-triggered path attempts the SAME outcome - must be a safe no-op.
        txTemplate().execute(status -> {
            saleCommitmentResolutionOrchestrator.resolve(setup.commitmentId(), FinalizationOutcome.B);
            return null;
        });

        SellerDiagnosticCommitment afterRetry = sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow();
        assertThat(afterRetry.getRefundAmount()).isEqualByComparingTo(beforeRetry.getRefundAmount());
    }

    // ==================== Concurrency ====================

    @Test
    @DisplayName("Concurrency: два одновременных resolve() на один commitment -> ровно одна ledger-мутация, конечное состояние согласовано")
    void resolve_ConcurrentFinalizationAttempts_ExactlyOneLedgerMutation() throws Exception {
        Setup setup = approveAndGetVehicleWithCommitment(new BigDecimal("10000"), new BigDecimal("4000"));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger successCount = new AtomicInteger(0);

        Runnable task = () -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                txTemplate().execute(status -> {
                    saleCommitmentResolutionOrchestrator.resolve(setup.commitmentId(), FinalizationOutcome.A);
                    return null;
                });
                successCount.incrementAndGet();
            } catch (Exception e) {
                // second attempt after the first commits sees FINALIZED/A already and no-ops
                // successfully too (idempotent) - only a genuinely conflicting/unexpected state
                // would throw, which this scenario never produces.
            }
        };

        executor.submit(task);
        executor.submit(task);
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        SellerDiagnosticCommitment finalState = sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow();
        assertThat(finalState.getStatus()).isEqualTo(CommitmentStatus.FINALIZED);
        assertThat(finalState.getFinalizationOutcome()).isEqualTo(FinalizationOutcome.A);
        assertThat(successCount.get()).isEqualTo(2);

        // Exactly one CHARGE/UNBLOCK ledger pair was ever written - not two. chargeAmount=0 for
        // A means no CHARGE row at all, only the UNBLOCK/refund row; verify there is exactly one.
        Deposit sellerDeposit = depositRepository.findByUserId(setup.sellerId()).orElseThrow();
        long refundTransactions = depositRepository.findTransactionsByUserId(setup.sellerId()).stream()
                .filter(t -> t.getType() == com.example.autoauction.deposit.domain.DepositTransaction.TransactionType.UNBLOCK
                        && ("DIAGNOSTIC_" + setup.requestId()).equals(t.getReference()))
                .count();
        assertThat(refundTransactions).isEqualTo(1);
    }
}
