// deposit/application/DepositReleasedEventAfterCommitPgIntegrationTest.java
package com.example.autoauction.deposit.application;

import com.example.autoauction.admin.application.AdminAuctionService;
import com.example.autoauction.auction.application.AuctionFinalizer;
import com.example.autoauction.auction.application.SaleAdministrationService;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.notification.application.NotificationService;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.sale.application.HandoverEvidenceService;
import com.example.autoauction.sale.application.PaymentEvidenceService;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleType;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * G2 ADVERSARIAL PROOF: {@link DepositReleasedEventListener}'s {@code AFTER_COMMIT} dispatch is
 * the sole, exactly-once side-effect path for {@code DepositService.releaseWinnerDeposit}, and a
 * genuinely rolled-back {@code completeSale} - via either of its two confirmed reachable
 * fail-closed points (a later seller-commitment ledger failure, or a legacy auction missing its
 * Settlement discriminator) - never leaks the DEPOSIT_RELEASED notification. Also proves the
 * identical guarantee for {@code AdminAuctionService.releaseWinner}, releaseWinnerDeposit's
 * second, independent production caller (G2 PLAN/VERIFY section B).
 *
 * TEST 2 reuses the exact real-database rollback mechanism already proven by
 * {@code SaleCommitmentResolutionRollbackIntegrationTest.completeSale_FailureAfterCommitmentClaim_RollsBackEverything}
 * (a {@code @SpyBean} wrapping the REAL DepositService, only {@code chargeForDiagnosticRequest}
 * stubbed to throw, scoped to the seller's own depositId/requestId - the winner's deposit calls
 * are untouched, real) - not a new mechanism.
 *
 * TEST 5 (a genuine concurrent @Version conflict specifically for
 * {@code AdminAuctionService.releaseWinner}'s own tiny transaction) is deliberately NOT
 * implemented: {@code @TransactionalEventListener(AFTER_COMMIT)} suppresses dispatch whenever the
 * enclosing transaction fails to commit, regardless of WHY it failed to commit - TEST 2 and
 * TEST 3 already prove that suppression mechanism end-to-end via two different real,
 * production-reachable trigger causes. Building a dedicated CountDownLatch/CyclicBarrier
 * concurrency harness (the D5-R/G1-Test-C technique) to reproduce the identical suppression via a
 * THIRD trigger (a raced Deposit @Version conflict) would add a large, self-contained harness for
 * a marginal proof of the same already-demonstrated mechanism - reported here per the G2
 * implementation instructions rather than built.
 *
 * Deliberately extends {@link AbstractPostgresIntegrationTest} WITHOUT any class-level
 * {@code @Transactional}: an {@code AFTER_COMMIT} listener never fires inside a test-managed
 * rollback-only transaction.
 */
@DisplayName("G2: DepositReleasedEvent AFTER_COMMIT — exactly-once side effect for both releaseWinnerDeposit callers, suppressed on rollback")
class DepositReleasedEventAfterCommitPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private SaleAdministrationService saleAdministrationService;
    @Autowired private AuctionFinalizer auctionFinalizer;
    @Autowired private AdminAuctionService adminAuctionService;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private DepositRepository depositRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private DiagnosticRequestService diagnosticRequestService;
    @Autowired private DiagnosticRequestRepository diagnosticRequestRepository;
    @Autowired private SellerDiagnosticCommitmentService sellerDiagnosticCommitmentService;
    @Autowired private SellerDiagnosticCommitmentRepository sellerDiagnosticCommitmentRepository;
    @Autowired private PaymentEvidenceService paymentEvidenceService;
    @Autowired private HandoverEvidenceService handoverEvidenceService;
    @Autowired private PolicyVersionService policyVersionService;
    @Autowired private PlatformTransactionManager transactionManager;

    @SpyBean private DepositService depositService;
    @SpyBean private NotificationService notificationServiceSpy;

    /** X3-E: freezeCommitment() snapshots whatever PolicyVersion is currently published. */
    @BeforeEach
    void publishBaselinePolicyVersion() {
        policyVersionService.publish(policyVersionService.create(new BigDecimal("0.02"), 1L).getId(), 2L, 0L);
    }

    private TransactionTemplate txTemplate() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return tx;
    }

    private String shortVin(String prefix) {
        return prefix + (System.nanoTime() % 1_000_000_000L);
    }

    private Long newUser() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "g2u" + System.nanoTime(), "g2u" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        return userRepository.save(u).getId();
    }

    private Long newUserWithDeposit(BigDecimal balance) {
        Long userId = newUser();
        depositService.createDeposit(userId, balance);
        return userId;
    }

    private void freezeAndMarkWinner(Long winnerId, Long auctionId, BigDecimal amount) {
        try {
            txTemplate().execute(status -> {
                depositService.createDeposit(winnerId, new BigDecimal("1000000"));
                return null;
            });
        } catch (Exception e) {
            // already exists (e.g. newUserWithDeposit already created it) - fine, reuse it.
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

    private Long createInAuctionVehicle() {
        return txTemplate().execute(status -> {
            Vehicle vehicle = new Vehicle("Toyota", "Camry", 2020, shortVin("G2"), VehicleType.SEDAN, 1L, "owner");
            vehicle.submitForReview();
            vehicle.approve(1L, "admin");
            vehicle.markInAuction();
            return vehicleRepository.save(vehicle).getId();
        });
    }

    /** Bare auction row - no Vehicle/commitment/evidence needed at all, since
     *  AdminAuctionService.releaseWinner never touches any of that (TEST 4/6). */
    private Long newBareTerminalAuction(Long winnerId, AuctionStatus status) {
        return txTemplate().execute(status2 -> {
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = new Auction(Math.abs(System.nanoTime() % 1_000_000L),
                    new BigDecimal("100000"), null, null, new BigDecimal("1000"),
                    now.minusDays(7), now.minusHours(1), 1L);
            auction.setStatus(status);
            auction.setWinnerId(winnerId);
            return auctionRepository.save(auction).getId();
        });
    }

    /** Legacy/malformed auction: no saleFinalizationMethod/policyVersionId/commitmentId, so
     *  settlementService.createPending fails closed on its FIRST guard (TEST 3). Mirrors
     *  AuctionSaleCompletionIntegrationTest's own plain createWaitingForPaymentAuction shape. */
    private Long createLegacyWaitingForPaymentAuction(Long vehicleId, Long winnerId, BigDecimal winningBid) {
        return txTemplate().execute(status -> {
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = new Auction(vehicleId, new BigDecimal("1000000"), null, null,
                    new BigDecimal("10000"), now.minusDays(7), now.minusHours(1), 10L);
            auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
            auction.setWinnerId(winnerId);
            auction.setWinningBid(winningBid);
            return auctionRepository.save(auction).getId();
        });
    }

    /** Full Settlement-capable wiring (policy + commitment + discriminator) - mirrors
     *  AuctionSaleCompletionIntegrationTest.createSettlementCapableWaitingForPaymentAuction
     *  exactly, so completeSale genuinely reaches SOLD (TEST 1). */
    private Long createSettlementCapableAuction(Long vehicleId, Long winnerId, BigDecimal winningBid) {
        return txTemplate().execute(status -> {
            Long sellerId = newUser();
            depositService.createDeposit(sellerId, new BigDecimal("50000"));

            DiagnosticRequest request = new DiagnosticRequest();
            request.setUserId(sellerId);
            request.setBrand("Toyota");
            request.setModel("Camry");
            request.setYear(2020);
            request.setVin(shortVin("G2S"));
            request.setDepositFrozen(new BigDecimal("10000"));
            request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
            request.setCreatedAt(OffsetDateTime.now());
            request.setUpdatedAt(OffsetDateTime.now());
            Long diagnosticRequestId = diagnosticRequestRepository.save(request).getId();

            Long policyVersionId = policyVersionService.create(new BigDecimal("0.02"), 1L).getId();
            policyVersionService.publish(policyVersionId, 2L, 0L);

            sellerDiagnosticCommitmentService.freezeCommitment(sellerId, diagnosticRequestId,
                    new BigDecimal("10000"), new BigDecimal("4000"));
            sellerDiagnosticCommitmentRepository.markAwaitingFinalization(diagnosticRequestId);
            Long commitmentId = sellerDiagnosticCommitmentRepository.findByDiagnosticRequestId(diagnosticRequestId)
                    .orElseThrow().getId();

            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = new Auction(vehicleId, new BigDecimal("1000000"), new BigDecimal("1000000"), null,
                    new BigDecimal("10000"), now.minusDays(7), now.minusHours(1), 10L);
            auction.setPolicyVersionId(policyVersionId);
            auction.setSellerDiagnosticCommitmentId(commitmentId);
            auction.setSaleFinalizationMethod(com.example.autoauction.auction.domain.SaleFinalizationMethod.ORDINARY_BID);
            auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
            auction.setWinnerId(winnerId);
            auction.setWinningBid(winningBid);
            return auctionRepository.save(auction).getId();
        });
    }

    private record Setup(Long vehicleId, Long commitmentId, Long requestId, Long depositId) {}

    /** Reuses SaleCommitmentResolutionRollbackIntegrationTest's own setup exactly (same real
     *  diagnostic-approval flow) - deliberately not shared code across test files, to keep this
     *  class's @SpyBean blast radius fully self-contained. */
    private Setup approveAndGetVehicleWithCommitment(BigDecimal committedAmount, BigDecimal feeAmount) {
        // Unlike Auction.winnerId/sellerId (established "actor reference, no FK" convention),
        // diagnostic_requests.user_id has a real FK to users - a bare out-of-thin-air literal
        // fails closed here, so a real User row is created, same as newUser()/newUserWithDeposit.
        Long sellerId = newUser();
        txTemplate().execute(status -> {
            depositService.createDeposit(sellerId, new BigDecimal("50000"));
            return null;
        });

        String vin = "G2RB" + (System.nanoTime() % 100000000L);
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

    private long uniqueWinnerId() {
        return 800_000L + (System.nanoTime() % 100_000L);
    }

    // ======================================================================
    // TEST 1: completeSale success (real production caller) — exactly one DEPOSIT_RELEASED.
    // ======================================================================

    @Test
    @DisplayName("TEST 1: completeSale success — sale completes, deposit release commits, exactly one DEPOSIT_RELEASED dispatch")
    void completeSale_Success_ExactlyOneDepositReleasedDispatch() {
        Long winnerId = newUserWithDeposit(new BigDecimal("1000000"));
        Long vehicleId = createInAuctionVehicle();
        BigDecimal winningBid = new BigDecimal("1020000.00");
        Long auctionId = createSettlementCapableAuction(vehicleId, winnerId, winningBid);
        txTemplate().execute(status -> paymentEvidenceService.recordPayment(auctionId, winningBid, 1L));
        txTemplate().execute(status -> handoverEvidenceService.recordHandover(auctionId, 1L));
        freezeAndMarkWinner(winnerId, auctionId, new BigDecimal("50000"));
        clearInvocations(notificationServiceSpy);

        saleAdministrationService.completeSale(auctionId, 1L);

        Auction persisted = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(AuctionStatus.SOLD);
        Deposit winnerDeposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(winnerDeposit.getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        verify(notificationServiceSpy, times(1)).send(eq(winnerId), eq("DEPOSIT_RELEASED"), anyString(), anyString());
    }

    // ======================================================================
    // TEST 2: completeSale rollback after release — reuses the EXISTING proven
    // commitment-claim-failure fixture (SaleCommitmentResolutionRollbackIntegrationTest).
    // ======================================================================

    @Test
    @DisplayName("TEST 2: completeSale rollback after release (commitment-claim failure) — zero DEPOSIT_RELEASED dispatches")
    void completeSale_RollbackAfterRelease_ViaCommitmentClaimFailure_ZeroDispatches() {
        Setup setup = approveAndGetVehicleWithCommitment(new BigDecimal("10000"), new BigDecimal("4000"));
        long winnerId = uniqueWinnerId();
        BigDecimal winningBid = new BigDecimal("1020000.00");
        Long auctionId = createWaitingForPaymentAuctionWithCommitment(setup.vehicleId(), setup.commitmentId(), winnerId, winningBid);
        freezeAndMarkWinner(winnerId, auctionId, new BigDecimal("50000"));
        txTemplate().execute(status -> paymentEvidenceService.recordPayment(auctionId, winningBid, 1L));
        txTemplate().execute(status -> handoverEvidenceService.recordHandover(auctionId, 1L));

        clearInvocations(notificationServiceSpy);
        doThrow(new RuntimeException("G2 injected test failure after commitment claim"))
                .when(depositService)
                .chargeForDiagnosticRequest(eq(setup.depositId()), eq(setup.requestId()), any(), any());

        assertThatThrownBy(() -> txTemplate().execute(status -> {
            Auction locked = auctionRepository.findByIdWithLock(auctionId).orElseThrow();
            auctionFinalizer.completeSale(locked, 1L);
            return null;
        })).isInstanceOf(RuntimeException.class).hasMessageContaining("injected test failure");

        Auction persistedAuction = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(persistedAuction.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        Deposit winnerDeposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(winnerDeposit.getBlockedAmount())
                .as("the winner deposit release itself must have been rolled back too")
                .isEqualByComparingTo("50000");

        verify(notificationServiceSpy, never()).send(eq(winnerId), eq("DEPOSIT_RELEASED"), anyString(), anyString());
    }

    // ======================================================================
    // TEST 3: completeSale rollback via settlement failure — legacy auction, no
    // saleFinalizationMethod, settlementService.createPending fails closed.
    // ======================================================================

    @Test
    @DisplayName("TEST 3: completeSale rollback via settlement failure (legacy auction, no saleFinalizationMethod) — zero DEPOSIT_RELEASED dispatches")
    void completeSale_RollbackViaSettlementFailure_ZeroDispatches() {
        Long winnerId = newUserWithDeposit(new BigDecimal("1000000"));
        Long vehicleId = createInAuctionVehicle();
        BigDecimal winningBid = new BigDecimal("1020000.00");
        Long auctionId = createLegacyWaitingForPaymentAuction(vehicleId, winnerId, winningBid);
        txTemplate().execute(status -> paymentEvidenceService.recordPayment(auctionId, winningBid, 1L));
        txTemplate().execute(status -> handoverEvidenceService.recordHandover(auctionId, 1L));
        freezeAndMarkWinner(winnerId, auctionId, new BigDecimal("50000"));
        clearInvocations(notificationServiceSpy);

        assertThatThrownBy(() -> txTemplate().execute(status -> {
            Auction locked = auctionRepository.findByIdWithLock(auctionId).orElseThrow();
            auctionFinalizer.completeSale(locked, 1L);
            return null;
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("saleFinalizationMethod");

        Auction persistedAuction = auctionRepository.findById(auctionId).orElseThrow();
        assertThat(persistedAuction.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        Deposit winnerDeposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(winnerDeposit.getBlockedAmount())
                .as("the winner deposit release itself must have been rolled back too")
                .isEqualByComparingTo("50000");

        verify(notificationServiceSpy, never()).send(eq(winnerId), eq("DEPOSIT_RELEASED"), anyString(), anyString());
    }

    // ======================================================================
    // TEST 4: standalone AdminAuctionService.releaseWinner success — releaseWinnerDeposit's
    // second, independent production caller.
    // ======================================================================

    @Test
    @DisplayName("TEST 4: standalone AdminAuctionService.releaseWinner success — exactly one DEPOSIT_RELEASED dispatch")
    void releaseWinner_StandaloneSuccess_ExactlyOneDepositReleasedDispatch() {
        Long winnerId = newUserWithDeposit(new BigDecimal("1000000"));
        Long auctionId = newBareTerminalAuction(winnerId, AuctionStatus.SOLD);
        freezeAndMarkWinner(winnerId, auctionId, new BigDecimal("50000"));
        clearInvocations(notificationServiceSpy);

        adminAuctionService.releaseWinner(auctionId);

        Deposit winnerDeposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(winnerDeposit.getBlockedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(notificationServiceSpy, times(1)).send(eq(winnerId), eq("DEPOSIT_RELEASED"), anyString(), anyString());
    }

    // TEST 5 (a genuine concurrent @Version conflict specifically for releaseWinner's own
    // transaction) - deliberately not implemented; see class Javadoc.

    // ======================================================================
    // TEST 6: post-commit notification failure — must not fail the caller or affect committed
    // state.
    // ======================================================================

    @Test
    @DisplayName("TEST 6: a post-commit notification failure does not fail releaseWinner or affect the already-committed release")
    void postCommitNotificationFailure_DoesNotFailReleaseWinnerOrAffectPersistedState() {
        Long winnerId = newUserWithDeposit(new BigDecimal("1000000"));
        Long auctionId = newBareTerminalAuction(winnerId, AuctionStatus.SOLD);
        freezeAndMarkWinner(winnerId, auctionId, new BigDecimal("50000"));

        doThrow(new RuntimeException("simulated notification failure"))
                .when(notificationServiceSpy).send(eq(winnerId), eq("DEPOSIT_RELEASED"), anyString(), anyString());

        assertThatCode(() -> adminAuctionService.releaseWinner(auctionId))
                .as("a post-commit notification failure must not propagate out of releaseWinner")
                .doesNotThrowAnyException();

        Deposit winnerDeposit = depositRepository.findByUserId(winnerId).orElseThrow();
        assertThat(winnerDeposit.getBlockedAmount())
                .as("the release remains committed despite the notification failure")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
