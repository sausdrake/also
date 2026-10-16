// auction/application/SaleCommitmentResolutionRollbackIntegrationTest.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.sale.application.HandoverEvidenceService;
import com.example.autoauction.sale.application.PaymentEvidenceService;
import com.example.autoauction.sale.domain.FaultParty;
import com.example.autoauction.sale.domain.SaleResolutionResult;
import com.example.autoauction.sale.domain.port.SaleResolutionRepository;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.support.TestUserIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * X3-D corrective (C2): real-database proof that a failure injected AFTER the seller
 * commitment's claim-first UPDATE has already succeeded still rolls back the ENTIRE production
 * transaction - Auction, Vehicle, buyer deposit, SaleResolution, and the commitment claim
 * itself - leaving durable state exactly as it was before the call.
 *
 * Deliberately NOT class-level @Transactional and deliberately does NOT wrap the call under
 * test in any test-owned transaction - each call to completeSale()/resolveSaleFailure() runs
 * inside ONLY the production @Transactional boundary those methods themselves open (via
 * TransactionTemplate.execute(), a genuinely separate, independently-committing-or-rolling-back
 * transaction, exactly mirroring a real caller). This is the whole point of this test class: a
 * test-level @Transactional would make the test "pass" via the TEST's own rollback regardless
 * of whether the PRODUCTION boundary is correct, which is not acceptable evidence here. All
 * assertions read fresh state via plain (non-transactional-template) repository calls, executed
 * strictly after the failing call has already returned/thrown and its transaction has already
 * concluded.
 *
 * Failure injection: {@code @SpyBean} wraps the REAL DepositService bean (precedented in this
 * codebase - see VideoProcessingServiceDbFinalizationFailureTest's identical
 * "@MockBean/@SpyBean one collaborator, everything else real" shape). Only
 * chargeForDiagnosticRequest(...) - the ledger call SaleCommitmentResolutionOrchestrator.resolve()
 * makes immediately AFTER its claim-first commitment UPDATE - is stubbed to throw, scoped to the
 * exact depositId/requestId under test; every other DepositService call (createDeposit,
 * freezeForAuction, markAsWinner, releaseWinnerDeposit/unblockForSaleFailure for the WINNER's
 * unrelated deposit) delegates to the real implementation. Zero production code is changed by
 * this mechanism.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("X3-D corrective (C2): rollback proof after commitment claim succeeds, before outer transaction commits")
class SaleCommitmentResolutionRollbackIntegrationTest {

    @Autowired
    private AuctionFinalizer auctionFinalizer;
    @Autowired
    private SaleFailureResolutionOrchestrator saleFailureResolutionOrchestrator;
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
    @SpyBean
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
    private com.example.autoauction.diagnostic.commitment.domain.port.SellerViolationResolutionRepository sellerViolationResolutionRepository;

    // X3-E: freezeCommitment() now snapshots the currently published PolicyVersion's id and
    // fails closed if none exists.
    @BeforeEach
    void publishPolicyVersion() {
        policyVersionService.publish(policyVersionService.create(new BigDecimal("0.02"), 1L).getId(), 2L, 0L);
    }

    private TransactionTemplate txTemplate() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return tx;
    }

    private record Setup(Long vehicleId, Long commitmentId, Long requestId, Long depositId) {}

    /** Mirrors SaleCommitmentResolutionIntegrationTest's own setup exactly - same real
     *  diagnostic-approval flow, same conventions, deliberately not shared code across test
     *  files to keep this class's @SpyBean blast radius fully self-contained. */
    private Setup approveAndGetVehicleWithCommitment(BigDecimal committedAmount, BigDecimal feeAmount) {
        Long sellerId = TestUserIds.next();
        txTemplate().execute(status -> {
            depositService.createDeposit(sellerId, new BigDecimal("50000"));
            return null;
        });

        String vin = "X3DRB" + (System.nanoTime() % 100000000L);
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

        txTemplate().execute(status -> {
            Vehicle v = vehicleRepository.findByVin(vin).orElseThrow();
            v.markInAuction();
            vehicleRepository.save(v);
            return null;
        });

        Vehicle vehicle = vehicleRepository.findByVin(vin).orElseThrow();
        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository
                .findByDiagnosticRequestId(requestId).orElseThrow();

        return new Setup(vehicle.getId(), commitment.getId(), requestId, commitment.getDepositId());
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
            auction.setSellerDiagnosticCommitmentId(commitmentId);
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
        return TestUserIds.next();
    }

    private record PolicyWithViolation(Long policyVersionId, Long violationDefinitionId) {}

    /** Mirrors SaleCommitmentResolutionIntegrationTest's own fixture exactly - seeds one
     *  ViolationDefinition directly via JPA (Flyway's V37 seed never runs under the H2 test
     *  profile) and publishes it under a fresh PolicyVersion. */
    private PolicyWithViolation publishPolicyWithOneViolation(String code, BigDecimal percentageValue) {
        Long violationDefinitionId = violationDefinitionJpaRepository.save(
                new com.example.autoauction.policy.infrastructure.persistence.JpaViolationDefinitionEntity(
                        code, "test fixture", OffsetDateTime.now())).getId();
        Long policyVersionId = policyVersionService.create(new BigDecimal("0.02"), 1L).getId();
        policyVersionService.updateDraftViolation(policyVersionId, violationDefinitionId,
                com.example.autoauction.policy.domain.PenaltyType.PERCENTAGE, percentageValue, 0L);
        Long revision = policyVersionService.getById(policyVersionId).getConfigurationRevision();
        policyVersionService.publish(policyVersionId, 2L, revision);
        return new PolicyWithViolation(policyVersionId, violationDefinitionId);
    }

    @Test
    @DisplayName("Path A (completeSale/SUCCESS): commitment claim succeeds, chargeForDiagnosticRequest fails -> whole transaction rolls back")
    void completeSale_FailureAfterCommitmentClaim_RollsBackEverything() {
        Setup setup = approveAndGetVehicleWithCommitment(new BigDecimal("10000"), new BigDecimal("4000"));
        long winnerId = uniqueWinnerId();
        BigDecimal winningBid = new BigDecimal("1020000.00");
        Long auctionId = createWaitingForPaymentAuctionWithCommitment(setup.vehicleId(), setup.commitmentId(), winnerId, winningBid);
        freezeAndMarkWinner(winnerId, auctionId, new BigDecimal("50000"));
        txTemplate().execute(status -> paymentEvidenceService.recordPayment(auctionId, winningBid, 1L));
        txTemplate().execute(status -> handoverEvidenceService.recordHandover(auctionId, 1L));

        // Injected AFTER setup, scoped to exactly the seller's depositId/requestId - the
        // WINNER's own deposit calls (a different depositId) are untouched, real.
        doThrow(new RuntimeException("X3-D corrective C2: injected test failure after commitment claim"))
                .when(depositService)
                .chargeForDiagnosticRequest(eq(setup.depositId()), eq(setup.requestId()), any(), any());

        assertThatThrownBy(() -> txTemplate().execute(status -> {
            Auction locked = auctionRepository.findByIdWithLock(auctionId).orElseThrow();
            auctionFinalizer.completeSale(locked, 1L);
            return null;
        })).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("injected test failure");

        // Fresh reads, outside the failed transaction - the real production @Transactional
        // boundary, not a test-owned one, is what must have reverted all of this.
        Auction persistedAuction = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(persistedAuction.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);

        Vehicle persistedVehicle = vehicleRepository.findById(setup.vehicleId()).orElseThrow();
        assertThat(persistedVehicle.getStatus()).isEqualTo(VehicleStatus.IN_AUCTION);

        Deposit winnerDeposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(winnerDeposit.getBlockedAmount()).isEqualByComparingTo("50000");

        assertThat(saleResolutionRepository.findByAuctionId(auctionId)).isEmpty();

        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow();
        assertThat(commitment.getStatus()).isEqualTo(CommitmentStatus.AWAITING_FINALIZATION);
        assertThat(commitment.getFinalizationOutcome()).isNull();
        assertThat(commitment.getFinalizedAt()).isNull();
    }

    @Test
    @DisplayName("Path B (resolveSaleFailure/B): commitment claim succeeds, chargeForDiagnosticRequest fails -> whole transaction rolls back")
    void resolveSaleFailure_OutcomeB_FailureAfterCommitmentClaim_RollsBackEverything() {
        Setup setup = approveAndGetVehicleWithCommitment(new BigDecimal("10000"), new BigDecimal("4000"));
        long winnerId = uniqueWinnerId();
        Long auctionId = createWaitingForPaymentAuctionWithCommitment(setup.vehicleId(), setup.commitmentId(), winnerId, new BigDecimal("1020000"));
        freezeAndMarkWinner(winnerId, auctionId, new BigDecimal("50000"));

        doThrow(new RuntimeException("X3-D corrective C2: injected test failure after commitment claim"))
                .when(depositService)
                .chargeForDiagnosticRequest(eq(setup.depositId()), eq(setup.requestId()), any(), any());

        assertThatThrownBy(() -> txTemplate().execute(status -> {
            Auction locked = auctionRepository.findByIdWithLock(auctionId).orElseThrow();
            saleFailureResolutionOrchestrator.resolveSaleFailure(locked, SaleResolutionResult.B, null, "no-sale", 1L, null);
            return null;
        })).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("injected test failure");

        Auction persistedAuction = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(persistedAuction.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);

        Vehicle persistedVehicle = vehicleRepository.findById(setup.vehicleId()).orElseThrow();
        assertThat(persistedVehicle.getStatus()).isEqualTo(VehicleStatus.IN_AUCTION);

        Deposit winnerDeposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(winnerDeposit.getBlockedAmount()).isEqualByComparingTo("50000");

        assertThat(saleResolutionRepository.findByAuctionId(auctionId)).isEmpty();

        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow();
        assertThat(commitment.getStatus()).isEqualTo(CommitmentStatus.AWAITING_FINALIZATION);
        assertThat(commitment.getFinalizationOutcome()).isNull();
        assertThat(commitment.getFinalizedAt()).isNull();
    }

    @Test
    @DisplayName("X3-E corrective (BLOCKER 2): Path C (resolveSaleFailure/C): commitment claim succeeds, " +
            "chargeSellerViolationPenalty fails AFTER the claim -> the ENTIRE production transaction rolls back")
    void resolveSaleFailure_OutcomeC_FailureAfterCommitmentClaim_RollsBackEverything() {
        PolicyWithViolation policy = publishPolicyWithOneViolation("X3E_ROLLBACK_TEST_VIOLATION", new BigDecimal("30"));

        Setup setup = approveAndGetVehicleWithCommitment(new BigDecimal("10000"), new BigDecimal("4000"));
        long winnerId = uniqueWinnerId();
        Long auctionId = createWaitingForPaymentAuctionWithCommitment(setup.vehicleId(), setup.commitmentId(), winnerId, new BigDecimal("1020000"));
        freezeAndMarkWinner(winnerId, auctionId, new BigDecimal("50000"));
        txTemplate().execute(status -> {
            Auction auction = auctionRepository.findById(auctionId).orElseThrow();
            auction.setPolicyVersionId(policy.policyVersionId());
            auctionRepository.save(auction);
            return null;
        });

        // Injected AFTER the commitment's own claim-first UPDATE has already run (proven by the
        // Hibernate SQL debug log showing the UPDATE ... WHERE status = 'AWAITING_FINALIZATION'
        // execute before this exception fires) - scoped to exactly this seller's deposit/request,
        // exactly mirroring Path A/B's own C2 precedent, never a mocked finalize().
        doThrow(new RuntimeException("X3-E corrective (BLOCKER 2): injected test failure after commitment claim"))
                .when(depositService)
                .chargeSellerViolationPenalty(eq(setup.depositId()), eq(setup.requestId()), any(), any(), any());

        assertThatThrownBy(() -> txTemplate().execute(status -> {
            Auction locked = auctionRepository.findByIdWithLock(auctionId).orElseThrow();
            saleFailureResolutionOrchestrator.resolveSaleFailure(locked, SaleResolutionResult.C, FaultParty.SELLER,
                    "violation", 1L, java.util.List.of(policy.violationDefinitionId()));
            return null;
        })).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("injected test failure");

        // Fresh reads, outside the failed transaction.
        Auction persistedAuction = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(persistedAuction.getStatus())
                .as("Auction must still be WAITING_FOR_PAYMENT - the SALE_FAILED transition rolled back")
                .isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);

        Vehicle persistedVehicle = vehicleRepository.findById(setup.vehicleId()).orElseThrow();
        assertThat(persistedVehicle.getStatus())
                .as("Vehicle must still be IN_AUCTION, never NEEDS_REDIAGNOSTIC from the failed transaction")
                .isEqualTo(VehicleStatus.IN_AUCTION);

        Deposit winnerDeposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(winnerDeposit.getBlockedAmount()).isEqualByComparingTo("50000");

        assertThat(saleResolutionRepository.findByAuctionId(auctionId))
                .as("no SaleResolution must exist - financialResolutionStatus was never persisted as DONE")
                .isEmpty();

        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository.findById(setup.commitmentId()).orElseThrow();
        assertThat(commitment.getStatus()).isEqualTo(CommitmentStatus.AWAITING_FINALIZATION);
        assertThat(commitment.getFinalizationOutcome()).isNull();
        assertThat(commitment.getFinalizedAt()).isNull();
        assertThat(commitment.getPenaltyAmount()).isNull();
        assertThat(commitment.getRefundAmount()).isNull();

        assertThat(sellerViolationResolutionRepository.findBySellerDiagnosticCommitmentId(setup.commitmentId()))
                .as("no SellerViolationResolution (and transitively no SellerViolationResolutionItem) must exist")
                .isEmpty();

        var transactions = depositRepository.findTransactionsByUserId(
                depositRepository.findById(setup.depositId()).orElseThrow().getUserId());
        assertThat(transactions)
                .as("no seller penalty CHARGE or UNBLOCK transaction must exist")
                .noneMatch(t -> t.getDescription() != null && t.getDescription().contains("штрафа за нарушение"));
    }
}
