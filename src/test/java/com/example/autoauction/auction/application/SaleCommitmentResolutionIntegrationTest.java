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
import com.example.autoauction.policy.application.PolicyVersionService;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private com.example.autoauction.admin.application.AdminAuctionService adminAuctionService;
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
    @Autowired
    private PolicyVersionService policyVersionService;
    @Autowired
    private com.example.autoauction.policy.infrastructure.persistence.SpringDataViolationDefinitionJpaRepository violationDefinitionJpaRepository;
    @Autowired
    private com.example.autoauction.diagnostic.commitment.application.SellerViolationResolutionEngine sellerViolationResolutionEngine;
    @Autowired
    private com.example.autoauction.diagnostic.commitment.domain.port.SellerViolationResolutionRepository sellerViolationResolutionRepository;

    // X3-E: freezeCommitment() now snapshots the currently published PolicyVersion's id and
    // fails closed if none exists.
    @BeforeEach
    void publishPolicyVersion() {
        policyVersionService.publish(policyVersionService.create(new BigDecimal("0.02"), 1L).getId());
    }

    /**
     * X3-E test fixture: seeds one ViolationDefinition row directly via the JPA layer
     * (bypassing ViolationDefinitionRepository, which is deliberately read-only in production -
     * this project's test profile has Flyway disabled, so V37's catalogue seed never runs
     * against the test database, same disclosed limitation as policy_version_counter's own
     * self-healing fallback), configures it under a NEW PolicyVersion draft with the given
     * PERCENTAGE penalty, and publishes it - returning the new PolicyVersion's id, which
     * becomes "current" (highest versionNumber) from this point on.
     */
    private record PolicyWithViolation(Long policyVersionId, Long violationDefinitionId) {}

    private PolicyWithViolation publishPolicyWithOneViolation(String code, BigDecimal percentageValue) {
        Long violationDefinitionId = violationDefinitionJpaRepository.save(
                new com.example.autoauction.policy.infrastructure.persistence.JpaViolationDefinitionEntity(
                        code, "test fixture", OffsetDateTime.now())).getId();
        Long policyVersionId = policyVersionService.create(new BigDecimal("0.02"), 1L).getId();
        policyVersionService.updateDraftViolation(policyVersionId, violationDefinitionId,
                com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE, percentageValue);
        policyVersionService.publish(policyVersionId);
        return new PolicyWithViolation(policyVersionId, violationDefinitionId);
    }

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
            saleFailureResolutionOrchestrator.resolveSaleFailure(locked, SaleResolutionResult.B, null, "no-sale", 1L, null);
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
            saleFailureResolutionOrchestrator.resolveSaleFailure(locked, SaleResolutionResult.BUYER_FAULT, FaultParty.BUYER, "no-show", 1L, null);
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
            saleFailureResolutionOrchestrator.resolveSaleFailure(locked, SaleResolutionResult.PLATFORM_FAULT, FaultParty.PLATFORM, "bad diagnosis", 1L, null);
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
            saleFailureResolutionOrchestrator.resolveSaleFailure(locked, SaleResolutionResult.B, null, "no-sale", 1L, null);
            return null;
        });

        SaleResolution resolution = saleResolutionRepository.findByAuctionId(auctionId).orElseThrow();
        assertThat(resolution.getFinancialResolutionStatus()).isEqualTo(FinancialResolutionStatus.DONE);

        Deposit winnerDeposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(winnerDeposit.getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ==================== Outcome C (X3-E): resolves via SellerViolationResolutionEngine ====================

    @Test
    @DisplayName("X3-E: C + real commitment + violationDefinitionIds -> commitment FINALIZED/C with correct " +
            "penalty/refund, financialResolutionStatus DONE, ledger charged with the dedicated seller-violation reason")
    void resolveSaleFailure_OutcomeC_WithCommitmentAndViolationIds_ResolvesViaEngine() {
        PolicyWithViolation policy = publishPolicyWithOneViolation("X3E_TEST_VIOLATION", new BigDecimal("30"));

        Setup setup = approveAndGetVehicleWithCommitment(new BigDecimal("10000"), new BigDecimal("4000"));
        long winnerId = uniqueWinnerId();
        Long auctionId = createWaitingForPaymentAuctionWithCommitment(setup.vehicleId(), setup.commitmentId(), winnerId, new BigDecimal("1020000"));
        freezeAndMarkWinner(winnerId, auctionId, new BigDecimal("50000"));
        // Mirrors what AdminAuctionService.createAuction would have snapshotted at creation -
        // this test builds the Auction manually (see createWaitingForPaymentAuctionWithCommitment),
        // so its own policyVersionId is set explicitly here instead.
        txTemplate().execute(status -> {
            Auction auction = auctionRepository.findById(auctionId).orElseThrow();
            auction.setPolicyVersionId(policy.policyVersionId());
            auctionRepository.save(auction);
            return null;
        });

        txTemplate().execute(status -> {
            Auction locked = auctionRepository.findByIdWithLock(auctionId).orElseThrow();
            saleFailureResolutionOrchestrator.resolveSaleFailure(locked, SaleResolutionResult.C, FaultParty.SELLER,
                    "violation", 1L, java.util.List.of(policy.violationDefinitionId()));
            return null;
        });

        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow();
        assertThat(commitment.getStatus()).isEqualTo(CommitmentStatus.FINALIZED);
        assertThat(commitment.getFinalizationOutcome()).isEqualTo(FinalizationOutcome.C);
        // 30% of the original frozen committedAmount (10000), never the remaining balance.
        assertThat(commitment.getPenaltyAmount()).isEqualByComparingTo("3000.00");
        assertThat(commitment.getRefundAmount()).isEqualByComparingTo("7000.00");

        SaleResolution resolution = saleResolutionRepository.findByAuctionId(auctionId).orElseThrow();
        assertThat(resolution.getFinancialResolutionStatus()).isEqualTo(FinancialResolutionStatus.DONE);

        Vehicle vehicle = vehicleRepository.findById(setup.vehicleId()).orElseThrow();
        assertThat(vehicle.getStatus()).isEqualTo(com.example.autoauction.vehicle.domain.VehicleStatus.NEEDS_REDIAGNOSTIC);

        var transactions = depositRepository.findTransactionsByUserId(setup.sellerId());
        assertThat(transactions)
                .as("seller ledger must use the dedicated seller-violation description, never chargeForDiagnosticRequest's " +
                        "diagnostic-commission wording")
                .anyMatch(t -> t.getType() == com.example.autoauction.deposit.domain.DepositTransaction.TransactionType.CHARGE
                        && t.getDescription().contains("штрафа за нарушение продавцом"));
    }

    @Test
    @DisplayName("X3-E: C + real commitment но violationDefinitionIds не передан -> IllegalArgumentException, " +
            "commitment остаётся AWAITING_FINALIZATION, вся транзакция откатывается")
    void resolveSaleFailure_OutcomeC_WithCommitmentNoViolationIds_FailsClosedWithFullRollback() {
        Setup setup = approveAndGetVehicleWithCommitment(new BigDecimal("10000"), new BigDecimal("4000"));
        long winnerId = uniqueWinnerId();
        Long auctionId = createWaitingForPaymentAuctionWithCommitment(setup.vehicleId(), setup.commitmentId(), winnerId, new BigDecimal("1020000"));
        freezeAndMarkWinner(winnerId, auctionId, new BigDecimal("50000"));

        assertThatThrownBy(() -> txTemplate().execute(status -> {
            Auction locked = auctionRepository.findByIdWithLock(auctionId).orElseThrow();
            saleFailureResolutionOrchestrator.resolveSaleFailure(locked, SaleResolutionResult.C, FaultParty.SELLER,
                    "violation", 1L, null);
            return null;
        })).isInstanceOf(IllegalArgumentException.class);

        Auction auction = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow();
        assertThat(commitment.getStatus()).isEqualTo(CommitmentStatus.AWAITING_FINALIZATION);
        assertThat(saleResolutionRepository.findByAuctionId(auctionId)).isEmpty();
    }

    // ==================== C1: fail-closed accounting guard (real DB) ====================

    @Test
    @DisplayName("X3-D corrective (C1): diagnosticFeeAmount > committedAmount -> fail closed, commitment stays AWAITING_FINALIZATION, no ledger mutation, no partial Auction/Vehicle/SaleResolution state")
    void resolveSaleFailure_OutcomeB_FeeExceedsCommittedAmount_FailsClosedWithFullRollback() {
        // freezeCommitment() itself has no cross-field validation preventing this (confirmed by
        // discovery: committedAmount/diagnosticFeeAmount are two independently admin-configured
        // SystemSettings values) - constructed here through the REAL production service call,
        // not by bypassing it.
        Setup setup = approveAndGetVehicleWithCommitment(new BigDecimal("10000"), new BigDecimal("15000"));
        long winnerId = uniqueWinnerId();
        Long auctionId = createWaitingForPaymentAuctionWithCommitment(setup.vehicleId(), setup.commitmentId(), winnerId, new BigDecimal("1020000"));
        freezeAndMarkWinner(winnerId, auctionId, new BigDecimal("50000"));

        assertThatThrownBy(() -> txTemplate().execute(status -> {
            Auction locked = auctionRepository.findByIdWithLock(auctionId).orElseThrow();
            saleFailureResolutionOrchestrator.resolveSaleFailure(locked, SaleResolutionResult.B, null, "no-sale", 1L, null);
            return null;
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("exceeding committedAmount");

        // Fresh reads, outside the failed transaction - full rollback proof.
        Auction persistedAuction = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(persistedAuction.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);

        Vehicle persistedVehicle = vehicleRepository.findById(setup.vehicleId()).orElseThrow();
        assertThat(persistedVehicle.getStatus()).isEqualTo(com.example.autoauction.vehicle.domain.VehicleStatus.IN_AUCTION);

        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow();
        assertThat(commitment.getStatus()).isEqualTo(CommitmentStatus.AWAITING_FINALIZATION);
        assertThat(commitment.getFinalizationOutcome()).isNull();

        assertThat(saleResolutionRepository.findByAuctionId(auctionId)).isEmpty();

        Deposit winnerDeposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(winnerDeposit.getBlockedAmount()).isEqualByComparingTo("50000");
    }

    // ==================== C3: commitment in the wrong state (FROZEN) ====================

    @Test
    @DisplayName("X3-D corrective (C3): commitment FROZEN (not yet AWAITING_FINALIZATION) -> fail closed, no ledger mutation, no partial Auction/Vehicle/SaleResolution state")
    void resolveSaleFailure_CommitmentStillFrozen_FailsClosedWithFullRollback() {
        // A commitment left at its natural creation-time status (FROZEN) - never approved or
        // rejected, so markAwaitingFinalization never ran. Attached to an independently-created
        // manual vehicle/auction (not the vehicle this diagnostic request actually belongs to) -
        // deliberately constructing a state X3-C's own creation-time eligibility check would
        // never itself allow, specifically to prove SaleCommitmentResolutionOrchestrator's own
        // independent AWAITING_FINALIZATION-only defense, not a realistic linkage.
        long sellerId = 900_000L + (System.nanoTime() % 100_000L);
        txTemplate().execute(status -> {
            depositService.createDeposit(sellerId, new BigDecimal("50000"));
            return null;
        });
        Long requestId = txTemplate().execute(status -> {
            DiagnosticRequest request = new DiagnosticRequest();
            request.setUserId(sellerId);
            request.setBrand("BMW");
            request.setModel("X5");
            request.setYear(2022);
            request.setVin("X3DFROZEN" + (System.nanoTime() % 100000000L));
            request.setDepositFrozen(new BigDecimal("10000"));
            request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
            request.setCreatedAt(OffsetDateTime.now());
            request.setUpdatedAt(OffsetDateTime.now());
            return diagnosticRequestRepository.save(request).getId();
        });
        Long frozenCommitmentId = txTemplate().execute(status -> {
            sellerDiagnosticCommitmentService.freezeCommitment(sellerId, requestId, new BigDecimal("10000"), new BigDecimal("4000"));
            return sellerDiagnosticCommitmentRepository.findByDiagnosticRequestId(requestId).orElseThrow().getId();
        });
        SellerDiagnosticCommitment frozen = sellerDiagnosticCommitmentRepository.findById(frozenCommitmentId).orElseThrow();
        assertThat(frozen.getStatus()).isEqualTo(CommitmentStatus.FROZEN);

        Vehicle manual = new Vehicle("Lada", "Vesta", 2022, "X3DFROZ" + (System.nanoTime() % 100000L),
                com.example.autoauction.vehicle.domain.VehicleType.SEDAN, 1L, "diagnostic");
        manual.submitForReview();
        manual.approve(999L, "admin");
        manual.markInAuction();
        Long vehicleId = txTemplate().execute(status -> vehicleRepository.save(manual).getId());

        long winnerId = uniqueWinnerId();
        Long auctionId = createWaitingForPaymentAuctionWithCommitment(vehicleId, frozenCommitmentId, winnerId, new BigDecimal("1020000"));
        freezeAndMarkWinner(winnerId, auctionId, new BigDecimal("50000"));

        assertThatThrownBy(() -> txTemplate().execute(status -> {
            Auction locked = auctionRepository.findByIdWithLock(auctionId).orElseThrow();
            saleFailureResolutionOrchestrator.resolveSaleFailure(locked, SaleResolutionResult.B, null, "no-sale", 1L, null);
            return null;
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("не готов к финализации");

        Auction persistedAuction = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(persistedAuction.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);

        Vehicle persistedVehicle = vehicleRepository.findById(vehicleId).orElseThrow();
        assertThat(persistedVehicle.getStatus()).isEqualTo(com.example.autoauction.vehicle.domain.VehicleStatus.IN_AUCTION);

        SellerDiagnosticCommitment stillFrozen = sellerDiagnosticCommitmentRepository.findById(frozenCommitmentId).orElseThrow();
        assertThat(stillFrozen.getStatus()).isEqualTo(CommitmentStatus.FROZEN);

        assertThat(saleResolutionRepository.findByAuctionId(auctionId)).isEmpty();

        Deposit winnerDeposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(winnerDeposit.getBlockedAmount()).isEqualByComparingTo("50000");
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

    // ==================== C4: releaseWinner status-guard matrix (real DB) ====================

    /** A plain, commitment-less auction (unrelated to X3-D's own commitment-linkage concern -
     *  releaseWinner's guard is status-only) with a real winner + real frozen deposit, in the
     *  given status, ready for adminAuctionService.releaseWinner(auctionId). */
    private Long createAuctionWithStatusAndFrozenWinner(AuctionStatus status, Long winnerId, BigDecimal amount) {
        Vehicle manual = new Vehicle("Lada", "Vesta", 2022, "X3DRW" + (System.nanoTime() % 100000000L),
                com.example.autoauction.vehicle.domain.VehicleType.SEDAN, 1L, "diagnostic");
        manual.submitForReview();
        manual.approve(999L, "admin");
        manual.markInAuction();
        Long vehicleId = txTemplate().execute(s -> vehicleRepository.save(manual).getId());

        Long auctionId = txTemplate().execute(s -> {
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = new Auction(vehicleId, new BigDecimal("1000000"), null, null,
                    new BigDecimal("10000"), now.minusDays(7), now.minusHours(1), 10L);
            auction.setStatus(status);
            auction.setWinnerId(winnerId);
            auction.setWinningBid(new BigDecimal("1020000"));
            return auctionRepository.save(auction).getId();
        });

        freezeAndMarkWinner(winnerId, auctionId, amount);
        return auctionId;
    }

    @Test
    @DisplayName("X3-D corrective (C4): releaseWinner rejects ACTIVE - deposit stays frozen")
    void releaseWinner_Active_RejectedDepositUntouched() {
        long winnerId = uniqueWinnerId();
        Long auctionId = createAuctionWithStatusAndFrozenWinner(AuctionStatus.ACTIVE, winnerId, new BigDecimal("50000"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adminAuctionService.releaseWinner(auctionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("исход ещё не определён");

        Deposit deposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(deposit.getBlockedAmount()).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("X3-D corrective (C4): releaseWinner rejects WAITING_FOR_PAYMENT - deposit stays frozen")
    void releaseWinner_WaitingForPayment_RejectedDepositUntouched() {
        long winnerId = uniqueWinnerId();
        Long auctionId = createAuctionWithStatusAndFrozenWinner(AuctionStatus.WAITING_FOR_PAYMENT, winnerId, new BigDecimal("50000"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adminAuctionService.releaseWinner(auctionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("исход ещё не определён");

        Deposit deposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(deposit.getBlockedAmount()).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("X3-D corrective (C4): releaseWinner preserves existing behavior for legacy COMPLETED - deposit released")
    @SuppressWarnings("deprecation")
    void releaseWinner_LegacyCompleted_AllowedDepositReleased() {
        long winnerId = uniqueWinnerId();
        Long auctionId = createAuctionWithStatusAndFrozenWinner(AuctionStatus.COMPLETED, winnerId, new BigDecimal("50000"));

        txTemplate().execute(s -> {
            adminAuctionService.releaseWinner(auctionId);
            return null;
        });

        Deposit deposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(deposit.getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("X3-D corrective (C4): releaseWinner preserves existing behavior for SOLD - deposit released")
    void releaseWinner_Sold_AllowedDepositReleased() {
        long winnerId = uniqueWinnerId();
        Long auctionId = createAuctionWithStatusAndFrozenWinner(AuctionStatus.SOLD, winnerId, new BigDecimal("50000"));

        txTemplate().execute(s -> {
            adminAuctionService.releaseWinner(auctionId);
            return null;
        });

        Deposit deposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(deposit.getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("X3-D corrective (C4): releaseWinner preserves existing behavior for SALE_FAILED - deposit released")
    void releaseWinner_SaleFailed_AllowedDepositReleased() {
        long winnerId = uniqueWinnerId();
        Long auctionId = createAuctionWithStatusAndFrozenWinner(AuctionStatus.SALE_FAILED, winnerId, new BigDecimal("50000"));

        txTemplate().execute(s -> {
            adminAuctionService.releaseWinner(auctionId);
            return null;
        });

        Deposit deposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(deposit.getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ==================== X3-E corrective (BLOCKER 3): real-DB concurrency proof ====================

    @Test
    @DisplayName("X3-E corrective (BLOCKER 3): two concurrent SellerViolationResolutionEngine.resolve() calls " +
            "against the SAME commitment, SAME violation set -> exactly one claim succeeds, exactly one " +
            "SellerViolationResolution exists, exactly one financial charge, no double charge, no duplicate audit")
    void resolve_ConcurrentAttemptsSameCommitmentSameViolations_ExactlyOneSucceeds() throws Exception {
        PolicyWithViolation policy = publishPolicyWithOneViolation("X3E_CONCURRENCY_TEST_VIOLATION", new BigDecimal("40"));
        Setup setup = approveAndGetVehicleWithCommitment(new BigDecimal("10000"), new BigDecimal("4000"));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        java.util.concurrent.atomic.AtomicReference<Throwable> errA = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> errB = new java.util.concurrent.atomic.AtomicReference<>();

        Runnable attempt = () -> txTemplate().execute(status -> {
            sellerViolationResolutionEngine.resolve(setup.commitmentId(), policy.policyVersionId(),
                    List.of(policy.violationDefinitionId()), 1L,
                    com.example.autoauction.diagnostic.commitment.domain.TriggerSource.DIAGNOSTIC_REJECTION, null);
            return null;
        });
        Runnable wrappedA = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                attempt.run();
            } catch (Throwable t) {
                errA.set(t);
            }
        };
        Runnable wrappedB = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                attempt.run();
            } catch (Throwable t) {
                errB.set(t);
            }
        };

        executor.submit(wrappedA);
        executor.submit(wrappedB);
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        // Both may legitimately observe success here: whichever thread's UPDATE loses the
        // race re-reads and finds the commitment already FINALIZED/C with this SAME violation
        // set - the engine's own documented idempotent-no-op path, not an error. What must be
        // exactly-once is the durable side effects below, never "which thread threw."
        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow();
        assertThat(commitment.getStatus()).isEqualTo(CommitmentStatus.FINALIZED);
        assertThat(commitment.getFinalizationOutcome()).isEqualTo(FinalizationOutcome.C);
        assertThat(commitment.getPenaltyAmount()).isEqualByComparingTo("4000.00");
        assertThat(commitment.getRefundAmount()).isEqualByComparingTo("6000.00");

        assertThat(sellerViolationResolutionRepository.findBySellerDiagnosticCommitmentId(setup.commitmentId()))
                .as("exactly one SellerViolationResolution must exist")
                .isPresent();
        Long resolutionId = sellerViolationResolutionRepository
                .findBySellerDiagnosticCommitmentId(setup.commitmentId()).orElseThrow().getId();
        assertThat(sellerViolationResolutionRepository.findItemsByResolutionId(resolutionId))
                .as("exactly one SellerViolationResolutionItem must exist - no duplicate audit row")
                .hasSize(1);

        Long sellerDepositId = commitment.getDepositId();
        Long sellerUserId = depositRepository.findById(sellerDepositId).orElseThrow().getUserId();
        var transactions = depositRepository.findTransactionsByUserId(sellerUserId);
        long chargeCount = transactions.stream()
                .filter(t -> t.getDescription() != null && t.getDescription().contains("штрафа за нарушение")
                        && t.getType() == com.example.autoauction.deposit.domain.DepositTransaction.TransactionType.CHARGE)
                .count();
        assertThat(chargeCount).as("exactly one seller-penalty CHARGE transaction - no double charge").isEqualTo(1L);
    }

    @Test
    @DisplayName("X3-E corrective (BLOCKER 3): conflicting retry - already FINALIZED/C with violation set {A}, " +
            "retried with a DIFFERENT violation set {B} -> fails closed, durable state from the first resolution unchanged")
    void resolve_ConflictingRetryDifferentViolationSet_FailsClosedStateUnchanged() {
        // Both violations configured on the SAME draft BEFORE it is published - a
        // PolicyVersionViolation cannot be added once its parent is published (immutable),
        // so both ids must resolve against one shared policyVersionId from the start.
        Long violationAId = violationDefinitionJpaRepository.save(
                new com.example.autoauction.policy.infrastructure.persistence.JpaViolationDefinitionEntity(
                        "X3E_CONFLICT_TEST_A", "test fixture", OffsetDateTime.now())).getId();
        Long violationBId = violationDefinitionJpaRepository.save(
                new com.example.autoauction.policy.infrastructure.persistence.JpaViolationDefinitionEntity(
                        "X3E_CONFLICT_TEST_B", "test fixture", OffsetDateTime.now())).getId();
        Long policyVersionId = policyVersionService.create(new BigDecimal("0.02"), 1L).getId();
        policyVersionService.updateDraftViolation(policyVersionId, violationAId,
                com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE, new BigDecimal("20"));
        policyVersionService.updateDraftViolation(policyVersionId, violationBId,
                com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE, new BigDecimal("50"));
        policyVersionService.publish(policyVersionId);

        Setup setup = approveAndGetVehicleWithCommitment(new BigDecimal("10000"), new BigDecimal("4000"));

        txTemplate().execute(status -> {
            sellerViolationResolutionEngine.resolve(setup.commitmentId(), policyVersionId,
                    List.of(violationAId), 1L,
                    com.example.autoauction.diagnostic.commitment.domain.TriggerSource.DIAGNOSTIC_REJECTION, null);
            return null;
        });

        SellerDiagnosticCommitment afterFirst = sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(CommitmentStatus.FINALIZED);
        assertThat(afterFirst.getPenaltyAmount()).isEqualByComparingTo("2000.00");

        assertThatThrownBy(() -> txTemplate().execute(status -> {
            sellerViolationResolutionEngine.resolve(setup.commitmentId(), policyVersionId,
                    List.of(violationBId), 1L,
                    com.example.autoauction.diagnostic.commitment.domain.TriggerSource.DIAGNOSTIC_REJECTION, null);
            return null;
        })).isInstanceOf(IllegalStateException.class);

        SellerDiagnosticCommitment afterConflict = sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow();
        assertThat(afterConflict.getPenaltyAmount())
                .as("penalty from the first, successful resolution must remain unchanged after the conflicting retry")
                .isEqualByComparingTo("2000.00");
        assertThat(sellerViolationResolutionRepository.findItemsByResolutionId(
                        sellerViolationResolutionRepository.findBySellerDiagnosticCommitmentId(setup.commitmentId()).orElseThrow().getId()))
                .as("only the first resolution's item must exist - the conflicting retry never persisted anything")
                .hasSize(1);
    }
}
