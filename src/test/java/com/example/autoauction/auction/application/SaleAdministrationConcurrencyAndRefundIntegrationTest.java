package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.SaleFinalizationMethod;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentService;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.sale.application.HandoverEvidenceService;
import com.example.autoauction.sale.application.PaymentEvidenceService;
import com.example.autoauction.sale.application.SaleResolutionService;
import com.example.autoauction.sale.domain.FaultParty;
import com.example.autoauction.sale.domain.SaleResolutionResult;
import com.example.autoauction.sale.domain.port.PaymentEvidenceRepository;
import com.example.autoauction.sale.domain.port.PaymentRefundEvidenceRepository;
import com.example.autoauction.sale.domain.port.SaleResolutionRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.VehicleType;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * Slice 1: real-DB proof of the locked concurrency and atomicity invariants for
 * {@link SaleAdministrationService} - the Auction pessimistic write lock serializes every
 * terminal operation, exactly one wins, the loser produces no durable side effect, and a
 * failure anywhere in the fail path rolls back the PaymentRefundEvidence together with the
 * SaleResolution and the Auction transition.
 *
 * {@code @SpyBean SaleResolutionService} wraps the real bean (precedented by
 * SaleCommitmentResolutionRollbackIntegrationTest); it is only stubbed in the one rollback
 * test and reset between methods.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Slice 1: SaleAdministrationService concurrency + refund atomicity (integration)")
class SaleAdministrationConcurrencyAndRefundIntegrationTest {

    @Autowired private SaleAdministrationService saleAdministrationService;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private DepositService depositService;
    @Autowired private PaymentEvidenceService paymentEvidenceService;
    @Autowired private HandoverEvidenceService handoverEvidenceService;
    @Autowired private PaymentEvidenceRepository paymentEvidenceRepository;
    @Autowired private PaymentRefundEvidenceRepository paymentRefundEvidenceRepository;
    @Autowired private SaleResolutionRepository saleResolutionRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DiagnosticRequestRepository diagnosticRequestRepository;
    @Autowired private SellerDiagnosticCommitmentRepository sellerDiagnosticCommitmentRepository;
    @Autowired private SellerDiagnosticCommitmentService sellerDiagnosticCommitmentService;
    @Autowired private PolicyVersionService policyVersionService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    @SpyBean private SaleResolutionService saleResolutionService;

    private final BigDecimal winningBid = new BigDecimal("1020000.00");
    private final Long adminId = 1L;

    private TransactionTemplate txTemplate() {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return t;
    }

    private record Fixture(Long auctionId, Long vehicleId, Long winnerId) {}

    private Fixture waitingForPayment(boolean withEvidence) {
        Long winnerId = 810_000L + (System.nanoTime() % 100_000_000L);
        Fixture f = txTemplate().execute(s -> {
            Vehicle vehicle = new Vehicle("Toyota", "Camry", 2020,
                    "SC" + (System.nanoTime() % 1_000_000_000L), VehicleType.SEDAN, 1L, "owner");
            vehicle.submitForReview();
            vehicle.approve(1L, "admin");
            vehicle.markInAuction();
            Long vehicleId = vehicleRepository.save(vehicle).getId();

            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = new Auction(vehicleId, new BigDecimal("1000000"), null, null,
                    new BigDecimal("10000"), now.minusDays(7), now.minusHours(1), 10L);
            auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
            auction.setWinnerId(winnerId);
            auction.setWinningBid(winningBid);
            // Slice 1B: this fixture bypasses AuctionFinalizer, so it must set its own initial
            // deadline directly - extendPaymentDeadline() requires an existing (non-null) one.
            auction.setPaymentDeadline(now.plusDays(1));
            Long auctionId = auctionRepository.save(auction).getId();
            return new Fixture(auctionId, vehicleId, winnerId);
        });

        txTemplate().execute(s -> {
            depositService.createDeposit(f.winnerId(), new BigDecimal("1000000"));
            return null;
        });
        txTemplate().execute(s -> {
            depositService.freezeForAuction(f.winnerId(), f.auctionId(), new BigDecimal("50000"));
            return null;
        });
        txTemplate().execute(s -> {
            depositService.markAsWinner(f.winnerId(), f.auctionId());
            return null;
        });

        if (withEvidence) {
            txTemplate().execute(s -> paymentEvidenceService.recordPayment(f.auctionId(), winningBid, adminId));
            txTemplate().execute(s -> handoverEvidenceService.recordHandover(f.auctionId(), adminId));
        }
        return f;
    }

    /**
     * Slice 1B: unlike {@link #waitingForPayment}, this fixture satisfies
     * {@code SettlementService.createPending}'s full precondition set (saleFinalizationMethod,
     * a real resolvable sellerDiagnosticCommitmentId, a real published policyVersionId) - the
     * ONLY way {@code completeSale} can genuinely reach {@code SOLD} rather than fail closed on
     * an unrelated, out-of-Slice-1B-scope Step 2C precondition. Mirrors
     * {@code AdminSaleApiIntegrationTest.waitingForPaymentWithSettlementPrerequisites}'s own
     * established pattern exactly, with Payment+Handover evidence also recorded so completeSale
     * itself has nothing else to fail on.
     */
    private Fixture waitingForPaymentWithSettlementPrerequisites() {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        Long sellerId = txTemplate().execute(s -> {
            User seller = new User(null, "dlsel" + System.nanoTime(), "dlsel" + System.nanoTime() + "@test.com",
                    "encoded", true, java.util.Set.of(userRole));
            Long id = userRepository.save(seller).getId();
            depositService.createDeposit(id, new BigDecimal("50000"));
            return id;
        });

        Long diagnosticRequestId = txTemplate().execute(s -> {
            DiagnosticRequest request = new DiagnosticRequest();
            request.setUserId(sellerId);
            request.setBrand("Toyota");
            request.setModel("Camry");
            request.setYear(2020);
            request.setVin("DL" + (System.nanoTime() % 100000000L));
            request.setDepositFrozen(new BigDecimal("10000"));
            request.setStatus(DiagnosticRequest.DiagnosticRequestStatus.PENDING);
            request.setCreatedAt(OffsetDateTime.now());
            request.setUpdatedAt(OffsetDateTime.now());
            return diagnosticRequestRepository.save(request).getId();
        });

        Long policyVersionId = txTemplate().execute(s -> {
            Long id = policyVersionService.create(new BigDecimal("0.02"), 1L).getId();
            policyVersionService.publish(id, 2L, 0L);
            return id;
        });

        Long commitmentId = txTemplate().execute(s -> {
            sellerDiagnosticCommitmentService.freezeCommitment(sellerId, diagnosticRequestId,
                    new BigDecimal("10000"), new BigDecimal("4000"));
            sellerDiagnosticCommitmentRepository.markAwaitingFinalization(diagnosticRequestId);
            return sellerDiagnosticCommitmentRepository.findByDiagnosticRequestId(diagnosticRequestId)
                    .orElseThrow().getId();
        });

        Long winnerId = 830_000L + (System.nanoTime() % 100_000_000L);
        Fixture f = txTemplate().execute(s -> {
            Vehicle vehicle = new Vehicle("Toyota", "Camry", 2020,
                    "SV" + (System.nanoTime() % 1_000_000_000L), VehicleType.SEDAN, 1L, "owner");
            vehicle.submitForReview();
            vehicle.approve(1L, "admin");
            vehicle.markInAuction();
            Long vehicleId = vehicleRepository.save(vehicle).getId();

            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = new Auction(vehicleId, new BigDecimal("1000000"), new BigDecimal("1000000"), null,
                    new BigDecimal("10000"), now.minusDays(7), now.minusHours(1), 10L);
            auction.setPolicyVersionId(policyVersionId);
            auction.setSellerDiagnosticCommitmentId(commitmentId);
            auction.setSaleFinalizationMethod(SaleFinalizationMethod.ORDINARY_BID);
            auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
            auction.setWinnerId(winnerId);
            auction.setWinningBid(winningBid);
            auction.setPaymentDeadline(now.plusDays(1));
            Long auctionId = auctionRepository.save(auction).getId();
            return new Fixture(auctionId, vehicleId, winnerId);
        });

        txTemplate().execute(s -> {
            depositService.createDeposit(f.winnerId(), new BigDecimal("1000000"));
            return null;
        });
        txTemplate().execute(s -> {
            depositService.freezeForAuction(f.winnerId(), f.auctionId(), new BigDecimal("50000"));
            return null;
        });
        txTemplate().execute(s -> {
            depositService.markAsWinner(f.winnerId(), f.auctionId());
            return null;
        });
        txTemplate().execute(s -> paymentEvidenceService.recordPayment(f.auctionId(), winningBid, adminId));
        txTemplate().execute(s -> handoverEvidenceService.recordHandover(f.auctionId(), adminId));

        return f;
    }

    // ==================== complete vs fail ====================

    @Test
    @DisplayName("Concurrent /complete vs /fail: ровно один терминальный исход; ровно один SaleResolution; согласованность")
    void concurrentCompleteVsFail_ExactlyOneWins() throws Exception {
        Fixture f = waitingForPayment(true);

        AtomicReference<Throwable> errComplete = new AtomicReference<>();
        AtomicReference<Throwable> errFail = new AtomicReference<>();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService ex = Executors.newFixedThreadPool(2);

        ex.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS); saleAdministrationService.completeSale(f.auctionId(), adminId); }
            catch (Throwable t) { errComplete.set(t); }
        });
        ex.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS);
                saleAdministrationService.failSale(f.auctionId(), SaleResolutionResult.B, null, null, adminId); }
            catch (Throwable t) { errFail.set(t); }
        });
        ex.shutdown();
        assertThat(ex.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        boolean completeFailed = errComplete.get() != null;
        boolean failFailed = errFail.get() != null;
        assertThat(completeFailed ^ failFailed)
                .as("exactly one of complete/fail must fail. errComplete=%s errFail=%s", errComplete.get(), errFail.get())
                .isTrue();

        Auction finalAuction = auctionRepository.findById(f.auctionId()).orElseThrow();
        assertThat(finalAuction.getStatus()).isIn(AuctionStatus.SOLD, AuctionStatus.SALE_FAILED);
        assertThat(saleResolutionRepository.findByAuctionId(f.auctionId())).isPresent();

        if (finalAuction.getStatus() == AuctionStatus.SOLD) {
            assertThat(paymentRefundEvidenceRepository.existsByAuctionId(f.auctionId()))
                    .as("SOLD must never carry refund evidence").isFalse();
        } else {
            assertThat(paymentRefundEvidenceRepository.findByAuctionId(f.auctionId()).orElseThrow()
                    .getRefundedAmount()).isEqualByComparingTo(winningBid);
        }
    }

    // ==================== fail vs fail ====================

    @Test
    @DisplayName("Concurrent /fail(BUYER_FAULT) vs /fail(C): ровно один исход; ровно одна refund-запись; ровно один SaleResolution")
    void concurrentFailVsFail_ExactlyOneWins() throws Exception {
        Fixture f = waitingForPayment(false);
        txTemplate().execute(s -> paymentEvidenceService.recordPayment(f.auctionId(), winningBid, adminId));

        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService ex = Executors.newFixedThreadPool(2);

        ex.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS);
                saleAdministrationService.failSale(f.auctionId(), SaleResolutionResult.BUYER_FAULT, null, null, adminId); }
            catch (Throwable t) { errA.set(t); }
        });
        ex.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS);
                saleAdministrationService.failSale(f.auctionId(), SaleResolutionResult.C, java.util.List.of(), null, adminId); }
            catch (Throwable t) { errB.set(t); }
        });
        ex.shutdown();
        assertThat(ex.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        assertThat((errA.get() != null) ^ (errB.get() != null))
                .as("exactly one fail must win. errA=%s errB=%s", errA.get(), errB.get()).isTrue();

        assertThat(auctionRepository.findById(f.auctionId()).orElseThrow().getStatus())
                .isEqualTo(AuctionStatus.SALE_FAILED);
        assertThat(saleResolutionRepository.findByAuctionId(f.auctionId())).isPresent();
        var refund = paymentRefundEvidenceRepository.findByAuctionId(f.auctionId());
        assertThat(refund).as("exactly one refund evidence").isPresent();
        assertThat(refund.orElseThrow().getRefundedAmount()).isEqualByComparingTo(winningBid);
    }

    // ==================== payment vs fail ====================

    @Test
    @DisplayName("Concurrent /payment vs /fail: никогда нет durable SALE_FAILED + PaymentEvidence без PaymentRefundEvidence")
    void concurrentPaymentVsFail_NoRefundInvariantViolation() throws Exception {
        Fixture f = waitingForPayment(false);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> errPay = new AtomicReference<>();
        AtomicReference<Throwable> errFail = new AtomicReference<>();

        ex.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS);
                txTemplate().execute(s -> paymentEvidenceService.recordPayment(f.auctionId(), winningBid, adminId)); }
            catch (Throwable t) { errPay.set(t); }
        });
        ex.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS);
                saleAdministrationService.failSale(f.auctionId(), SaleResolutionResult.B, null, null, adminId); }
            catch (Throwable t) { errFail.set(t); }
        });
        ex.shutdown();
        assertThat(ex.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        Auction finalAuction = auctionRepository.findById(f.auctionId()).orElseThrow();
        boolean paymentExists = paymentEvidenceRepository.existsByAuctionId(f.auctionId());
        boolean refundExists = paymentRefundEvidenceRepository.existsByAuctionId(f.auctionId());

        if (finalAuction.getStatus() == AuctionStatus.SALE_FAILED && paymentExists) {
            assertThat(refundExists)
                    .as("durable SALE_FAILED with PaymentEvidence MUST have matching PaymentRefundEvidence")
                    .isTrue();
        }
        // The only other legal end states: SALE_FAILED with no payment (payment lost the race),
        // or still WAITING_FOR_PAYMENT is impossible here (fail always succeeds unless it lost to
        // nothing) - fail has no competing terminal op, so it always commits SALE_FAILED.
        assertThat(finalAuction.getStatus()).isEqualTo(AuctionStatus.SALE_FAILED);
    }

    // ==================== refund atomicity / rollback ====================

    @Test
    @DisplayName("Откат fail-транзакции после вставки refund evidence: ничего не сохраняется (refund, SaleResolution, статус)")
    void failRollbackAfterRefundInsert_EverythingRollsBack() {
        Fixture f = waitingForPayment(false);
        txTemplate().execute(s -> paymentEvidenceService.recordPayment(f.auctionId(), winningBid, adminId));

        // createFailure runs AFTER paymentRefundService.recordRefund in the same physical
        // transaction - forcing it to throw proves the refund insert is rolled back with it.
        doThrow(new RuntimeException("injected: createFailure failed after refund insert"))
                .when(saleResolutionService).createFailure(anyLong(), any(), any(), any(), anyLong());

        assertThatThrownBy(() ->
                saleAdministrationService.failSale(f.auctionId(), SaleResolutionResult.BUYER_FAULT, null, null, adminId))
                .isInstanceOf(RuntimeException.class);

        assertThat(auctionRepository.findById(f.auctionId()).orElseThrow().getStatus())
                .isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        assertThat(paymentRefundEvidenceRepository.existsByAuctionId(f.auctionId()))
                .as("refund evidence must have been rolled back").isFalse();
        assertThat(saleResolutionRepository.existsByAuctionId(f.auctionId())).isFalse();
        assertThat(vehicleRepository.findById(f.vehicleId()).orElseThrow().getStatus())
                .isEqualTo(VehicleStatus.IN_AUCTION);
    }

    // ==================== refund not created when no payment ====================

    @Test
    @DisplayName("Fail без PaymentEvidence: SALE_FAILED, но PaymentRefundEvidence не создаётся")
    void failWithoutPayment_NoRefundEvidence() {
        Fixture f = waitingForPayment(false);

        saleAdministrationService.failSale(f.auctionId(), SaleResolutionResult.PLATFORM_FAULT, null, null, adminId);

        assertThat(auctionRepository.findById(f.auctionId()).orElseThrow().getStatus())
                .isEqualTo(AuctionStatus.SALE_FAILED);
        assertThat(paymentRefundEvidenceRepository.existsByAuctionId(f.auctionId())).isFalse();
        assertThat(saleResolutionRepository.findByAuctionId(f.auctionId()).orElseThrow().getFaultParty())
                .isEqualTo(FaultParty.PLATFORM);
    }

    // ==================== Slice 1B: extendPaymentDeadline concurrency ====================

    @Test
    @DisplayName("Slice 1B: concurrent /deadline/extend vs /deadline/extend: ровно один проигрывает (OptimisticLockingFailureException), нет потерянного обновления")
    void concurrentExtendVsExtend_NoLostUpdate() throws Exception {
        Fixture f = waitingForPayment(false);

        AtomicReference<Throwable> err1 = new AtomicReference<>();
        AtomicReference<Throwable> err2 = new AtomicReference<>();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        // Truncated to microseconds - PostgreSQL's TIMESTAMP WITH TIME ZONE precision is
        // microseconds, while Java's OffsetDateTime.now() carries nanoseconds; comparing the
        // reloaded (DB-rounded) value against an untruncated candidate would spuriously fail.
        OffsetDateTime candidateA = OffsetDateTime.now().plusDays(2).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        OffsetDateTime candidateB = OffsetDateTime.now().plusDays(3).truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        ex.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS);
                saleAdministrationService.extendPaymentDeadline(f.auctionId(), candidateA, "admin A", adminId); }
            catch (Throwable t) { err1.set(t); }
        });
        ex.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS);
                saleAdministrationService.extendPaymentDeadline(f.auctionId(), candidateB, "admin B", adminId); }
            catch (Throwable t) { err2.set(t); }
        });
        ex.shutdown();
        assertThat(ex.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        boolean oneFailed = (err1.get() != null) ^ (err2.get() != null);
        boolean bothSucceeded = err1.get() == null && err2.get() == null;
        assertThat(oneFailed || bothSucceeded)
                .as("either exactly one loses the optimistic-lock race, or the pessimistic lock serializes both - never silent data loss")
                .isTrue();

        Auction finalAuction = auctionRepository.findById(f.auctionId()).orElseThrow();
        assertThat(finalAuction.getPaymentDeadline()).isIn(candidateA, candidateB);
        // Exactly one ADMIN_EXTENSION history row per successful call - never fewer than
        // succeeded, never more.
        long successCount = (err1.get() == null ? 1 : 0) + (err2.get() == null ? 1 : 0);
        long historyRows = saleAdministrationService.getPaymentDeadlineHistory(f.auctionId()).stream()
                .filter(h -> h.getChangeType().name().equals("ADMIN_EXTENSION"))
                .count();
        assertThat(historyRows).isEqualTo(successCount);
    }

    @Test
    @DisplayName("Slice 1B: concurrent /deadline/extend vs /complete: completeSale всегда успешен (не гейтится дедлайном), экстеншн не теряется")
    void concurrentExtendVsComplete_NoLostUpdate() throws Exception {
        // Genuinely valid fixture (saleFinalizationMethod + real resolvable
        // sellerDiagnosticCommitmentId + real published policyVersionId + recorded Payment/
        // Handover evidence) - the ONLY way completeSale can truly reach SOLD, so this test
        // proves the real claim (locked rule: completeSale never gates on paymentDeadline), not
        // a weakened one that merely tolerates completeSale failing for an unrelated reason.
        Fixture f = waitingForPaymentWithSettlementPrerequisites();
        // Captured BEFORE the race starts - the only correct reference point for "the extension
        // never happened" in outcome B below. Asserting against anything else (e.g. just "not
        // newDeadline") would not actually prove the deadline was left untouched.
        OffsetDateTime originalDeadline = auctionRepository.findById(f.auctionId()).orElseThrow().getPaymentDeadline();

        AtomicReference<Throwable> errExtend = new AtomicReference<>();
        AtomicReference<Throwable> errComplete = new AtomicReference<>();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        OffsetDateTime newDeadline = OffsetDateTime.now().plusDays(5);

        ex.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS);
                saleAdministrationService.extendPaymentDeadline(f.auctionId(), newDeadline, "admin extend", adminId); }
            catch (Throwable t) { errExtend.set(t); }
        });
        ex.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS); saleAdministrationService.completeSale(f.auctionId(), adminId); }
            catch (Throwable t) { errComplete.set(t); }
        });
        ex.shutdown();
        assertThat(ex.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        // completeSale never reads/gates on paymentDeadline (locked rule) - it must ALWAYS
        // succeed here, regardless of which operation's pessimistic lock (findByIdWithLock) wins
        // the race.
        assertThat(errComplete.get()).as("completeSale must never be blocked by a concurrent deadline extension").isNull();

        Auction finalAuction = auctionRepository.findById(f.auctionId()).orElseThrow();
        assertThat(finalAuction.getStatus()).isEqualTo(AuctionStatus.SOLD);

        if (errExtend.get() == null) {
            // Outcome A: extend won the lock race - it succeeded while still WAITING_FOR_PAYMENT.
            // completeSale still succeeded afterward (asserted above); the extension's write must
            // be durably reflected, never lost.
            assertThat(finalAuction.getPaymentDeadline())
                    .as("a successful extension must never be silently lost by a concurrent completeSale transaction")
                    .isCloseTo(newDeadline, within(1, java.time.temporal.ChronoUnit.SECONDS));
        } else {
            // Outcome B: completeSale won the lock race - the auction was already SOLD by the
            // time extend's lock was granted, so the domain's own status guard
            // (Auction.extendPaymentDeadline) correctly, deliberately rejected it. This is valid
            // behavior, not a lost update - but it must be EXACTLY this documented guard (never a
            // lock timeout/deadlock/other transaction exception), and the deadline must remain at
            // its ORIGINAL pre-race value, not merely "different from newDeadline".
            assertThat(errExtend.get())
                    .as("extend must fail closed with the documented status guard, not some other error")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Изменение дедлайна доступно только в статусе 'WAITING_FOR_PAYMENT'");
            assertThat(finalAuction.getPaymentDeadline())
                    .as("a rejected extension must leave the deadline exactly at its original pre-race value")
                    .isCloseTo(originalDeadline, within(1, java.time.temporal.ChronoUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("Slice 1B: completeSale успешен и после обычного (неконкурентного) продления дедлайна")
    void extendThenComplete_Sequential_CompleteSaleStillSucceeds() {
        Fixture f = waitingForPaymentWithSettlementPrerequisites();

        saleAdministrationService.extendPaymentDeadline(
                f.auctionId(), OffsetDateTime.now().plusDays(10), "покупатель попросил отсрочку", adminId);

        saleAdministrationService.completeSale(f.auctionId(), adminId);

        assertThat(auctionRepository.findById(f.auctionId()).orElseThrow().getStatus())
                .isEqualTo(AuctionStatus.SOLD);
    }

    @Test
    @DisplayName("Slice 1B: concurrent /deadline/extend vs /fail: оба сериализуются через блокировку Auction, нет потерянного обновления")
    void concurrentExtendVsFail_NoLostUpdate() throws Exception {
        Fixture f = waitingForPayment(false);
        // Captured BEFORE the race starts - the only correct reference point for "the extension
        // never happened" in outcome B below.
        OffsetDateTime originalDeadline = auctionRepository.findById(f.auctionId()).orElseThrow().getPaymentDeadline();

        AtomicReference<Throwable> errExtend = new AtomicReference<>();
        AtomicReference<Throwable> errFail = new AtomicReference<>();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        OffsetDateTime newDeadline = OffsetDateTime.now().plusDays(5);

        ex.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS);
                saleAdministrationService.extendPaymentDeadline(f.auctionId(), newDeadline, "admin extend", adminId); }
            catch (Throwable t) { errExtend.set(t); }
        });
        ex.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS);
                saleAdministrationService.failSale(f.auctionId(), SaleResolutionResult.B, null, null, adminId); }
            catch (Throwable t) { errFail.set(t); }
        });
        ex.shutdown();
        assertThat(ex.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        // failSale() never gates on paymentDeadline either (locked rule) - must always succeed,
        // regardless of lock-acquisition ordering. extend's own outcome is timing-dependent -
        // see the identical reasoning in concurrentExtendVsComplete_NoLostUpdate.
        assertThat(errFail.get()).as("failSale must never be blocked by a deadline extension").isNull();
        Auction finalAuction = auctionRepository.findById(f.auctionId()).orElseThrow();
        assertThat(finalAuction.getStatus()).isEqualTo(AuctionStatus.SALE_FAILED);

        if (errExtend.get() == null) {
            // Outcome A: extend won the lock race - failSale still succeeded afterward (asserted
            // above); the extension's write must be durably reflected, never lost.
            assertThat(finalAuction.getPaymentDeadline())
                    .as("a successful extension must never be silently lost by a concurrent failSale transaction")
                    .isCloseTo(newDeadline, within(1, java.time.temporal.ChronoUnit.SECONDS));
        } else {
            // Outcome B: failSale won the lock race - the auction was already SALE_FAILED by the
            // time extend's lock was granted, so the domain's own status guard correctly rejected
            // it. Valid behavior, not a lost update - but it must be EXACTLY this guard, and the
            // deadline must remain at its ORIGINAL pre-race value.
            assertThat(errExtend.get())
                    .as("extend must fail closed with the documented status guard, not some other error")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Изменение дедлайна доступно только в статусе 'WAITING_FOR_PAYMENT'");
            assertThat(finalAuction.getPaymentDeadline())
                    .as("a rejected extension must leave the deadline exactly at its original pre-race value")
                    .isCloseTo(originalDeadline, within(1, java.time.temporal.ChronoUnit.SECONDS));
        }
    }

    // ==================== Slice 1B: overdue is informational only (negative business semantics) ====================

    @Test
    @DisplayName("Slice 1B: просроченный дедлайн сам по себе не меняет ничего - AuctionStatus, SaleResolution и депозит победителя остаются нетронутыми")
    void overdueDeadline_ByItself_ChangesNothing() {
        Fixture f = waitingForPayment(false);

        // Push the deadline into the past - simulates time having passed, with zero scheduler
        // or automation ever acting on it (confirmed absent from this codebase).
        saleAdministrationService.extendPaymentDeadline(
                f.auctionId(), OffsetDateTime.now().minusDays(10), "симуляция просроченного дедлайна", adminId);

        Auction stillWaiting = auctionRepository.findById(f.auctionId()).orElseThrow();
        assertThat(stillWaiting.getPaymentDeadline()).isBefore(OffsetDateTime.now());
        assertThat(stillWaiting.getStatus())
                .as("overdue must never by itself change AuctionStatus")
                .isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        assertThat(saleResolutionRepository.existsByAuctionId(f.auctionId()))
                .as("overdue must never by itself create a SaleResolution")
                .isFalse();
        assertThat(depositService.getFrozenAmountForAuction(f.winnerId(), f.auctionId()))
                .as("overdue must never by itself mutate the winner's frozen deposit")
                .isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("Slice 1B: completeSale остаётся доступен администратору даже после просроченного дедлайна")
    void completeSale_SucceedsEvenWhenOverdue() {
        Fixture f = waitingForPaymentWithSettlementPrerequisites();

        saleAdministrationService.extendPaymentDeadline(
                f.auctionId(), OffsetDateTime.now().minusDays(10), "симуляция просроченного дедлайна", adminId);

        saleAdministrationService.completeSale(f.auctionId(), adminId);

        assertThat(auctionRepository.findById(f.auctionId()).orElseThrow().getStatus())
                .as("completeSale must never be gated by an overdue deadline")
                .isEqualTo(AuctionStatus.SOLD);
    }

    @Test
    @DisplayName("Slice 1B: failSale остаётся доступен администратору даже после просроченного дедлайна")
    void failSale_SucceedsEvenWhenOverdue() {
        Fixture f = waitingForPayment(false);

        saleAdministrationService.extendPaymentDeadline(
                f.auctionId(), OffsetDateTime.now().minusDays(10), "симуляция просроченного дедлайна", adminId);

        saleAdministrationService.failSale(f.auctionId(), SaleResolutionResult.B, null, null, adminId);

        assertThat(auctionRepository.findById(f.auctionId()).orElseThrow().getStatus())
                .as("failSale must never be gated by an overdue deadline")
                .isEqualTo(AuctionStatus.SALE_FAILED);
    }
}
