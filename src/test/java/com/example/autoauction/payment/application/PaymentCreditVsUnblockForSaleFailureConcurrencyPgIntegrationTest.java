// payment/application/PaymentCreditVsUnblockForSaleFailureConcurrencyPgIntegrationTest.java
package com.example.autoauction.payment.application;

import com.example.autoauction.auction.application.SaleAdministrationService;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.FrozenDeposit;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.port.PaymentProviderPort;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.payment.infrastructure.provider.FakePaymentProviderPort;
import com.example.autoauction.sale.domain.SaleResolutionResult;
import com.example.autoauction.sale.domain.port.PaymentRefundEvidenceRepository;
import com.example.autoauction.sale.domain.port.SaleResolutionRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.VehicleType;
import com.example.autoauction.vehicle.domain.port.VehicleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * D5-U: real-PostgreSQL proof of the actual semantics between
 * {@link DepositService#creditFromPayment} (pessimistic {@code SELECT ... FOR UPDATE} via
 * {@code findByUserIdForUpdate}, held continuously) and
 * {@link DepositService#unblockForSaleFailure} (unlocked {@code findByUserId} read, plain
 * deferred {@code save()}) - structurally the same asymmetric shape already proven safe for
 * {@code withdraw} (D5-A), {@code chargeForDiagnosticRequest} (D5-D), {@code chargeSellerViolationPenalty}
 * (D5-E), and {@code adminAdjust} (D5-F).
 *
 * <p>The genuinely NEW claim this test exists to prove - confirmed by direct source read during
 * AUDIT/RECON, not assumed - is the transaction BLAST RADIUS: {@code unblockForSaleFailure} is
 * called only from {@link com.example.autoauction.auction.application.SaleFailureResolutionOrchestrator#resolveSaleFailure},
 * a single flat {@code @Transactional} (REQUIRED, deliberately NOT {@code REQUIRES_NEW} - its own
 * Javadoc states "a failure here must roll back the whole outcome resolution"), itself joined by
 * {@link SaleAdministrationService#failSale} (the real, outermost, admin-triggered production
 * entry point, which owns the Auction pessimistic lock). For Outcome B with no
 * {@code PaymentEvidence} and no linked seller commitment (this fixture's exact shape, confirmed
 * by tracing {@code resolveSaleFailure}'s source), the ONLY authoritative mutations in that
 * transaction are: {@code SaleResolution} creation, {@code Vehicle.returnToApproved} (IN_AUCTION ->
 * APPROVED), {@code Auction.markSaleFailed()} (persisted via {@code auctionRepository.save} at the
 * end), and the Deposit/FrozenDeposit/UNBLOCK-ledger triad inside {@code unblockForSaleFailure}
 * itself. No {@code PaymentRefundEvidence} is ever created by this exact fixture (no
 * {@code PaymentEvidence} row exists), confirmed by source: {@code resolveSaleFailure} only calls
 * {@code paymentRefundService.recordRefund} when {@code paymentEvidenceRepository.existsByAuctionId(...)}
 * is true - so this test does not assert on it as a rollback proof (it is absent either way), only
 * confirms it stays absent for completeness.
 *
 * <p>Both financial operations are driven through their real production entry points -
 * {@link PaymentConfirmationOrchestrator#confirmPayment} and {@link SaleAdministrationService#failSale} -
 * never by calling {@code DepositService} methods directly. Synchronization reuses the exact
 * D5-D/D5-E/D5-F pattern (not D5-H's mutual-unlocked pattern): only the losing side's
 * {@code save(Deposit)} is gated, since {@code creditFromPayment}'s pessimistic lock means it can
 * never itself lose regardless of read timing - a plain {@code SELECT} by {@code unblockForSaleFailure}
 * is never blocked by credit's uncommitted {@code FOR UPDATE}, so gating that read would be
 * artificial (same reasoning already validated in D5-D/E/F's own adversarial audits).
 */
@DisplayName("D5-U: creditFromPayment vs unblockForSaleFailure — real-PostgreSQL concurrency proof")
class PaymentCreditVsUnblockForSaleFailureConcurrencyPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PaymentConfirmationOrchestrator orchestrator;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private FakePaymentProviderPort fakeProvider;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private SaleAdministrationService saleAdministrationService;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private SaleResolutionRepository saleResolutionRepository;
    @Autowired private PaymentRefundEvidenceRepository paymentRefundEvidenceRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    @SpyBean private DepositRepository depositRepositorySpy;

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000000.00");
    private static final BigDecimal FREEZE_AMOUNT = new BigDecimal("50000.00");
    private static final BigDecimal CREDIT_AMOUNT = new BigDecimal("7000.00");
    private static final Long ADMIN_ID = 1L;
    private static final long WAIT_SECONDS = 8;
    private static final long JOIN_TIMEOUT_MS = 20_000;

    private record Fixture(Long auctionId, Long vehicleId, Long winnerId, Long depositId) {}

    /**
     * Real WAITING_FOR_PAYMENT auction with a winner-flagged FrozenDeposit, mirroring
     * SaleAdministrationConcurrencyAndRefundIntegrationTest.waitingForPayment's own established
     * fixture shape - EXCEPT the winner MUST be a real, persisted User row here (unlike that
     * sibling test's raw sentinel id), because {@code payment.user_id} carries a genuine FK to
     * {@code users(id)} (confirmed via migration read) which {@code deposits.user_id} does not -
     * this fixture needs both a real Payment and a real Deposit for the same user.
     */
    private Fixture newWaitingForPaymentAuction() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "d5u" + System.nanoTime(), "d5u" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long winnerId = userRepository.save(u).getId();

        Vehicle vehicle = new Vehicle("Toyota", "Camry", 2020,
                "D5U" + (System.nanoTime() % 1_000_000_000L), VehicleType.SEDAN, 1L, "owner");
        vehicle.submitForReview();
        vehicle.approve(1L, "admin");
        vehicle.markInAuction();
        Long vehicleId = vehicleRepository.save(vehicle).getId();

        OffsetDateTime now = OffsetDateTime.now();
        Auction auction = new Auction(vehicleId, new BigDecimal("1000000.00"), null, null,
                new BigDecimal("10000.00"), now.minusDays(7), now.minusHours(1), 10L);
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        auction.setWinnerId(winnerId);
        auction.setWinningBid(new BigDecimal("1020000.00"));
        // Slice 1B: this fixture bypasses AuctionFinalizer, so it must set its own initial
        // deadline directly - same established convention as the sibling fixture.
        auction.setPaymentDeadline(now.plusDays(1));
        Long auctionId = auctionRepository.save(auction).getId();

        depositService.createDeposit(winnerId, INITIAL_BALANCE);
        depositService.freezeForAuction(winnerId, auctionId, FREEZE_AMOUNT);
        depositService.markAsWinner(winnerId, auctionId);

        Long depositId = depositRepository.findByUserId(winnerId).orElseThrow().getId();
        return new Fixture(auctionId, vehicleId, winnerId, depositId);
    }

    private String newSucceedingPayment(Long userId, BigDecimal amount) {
        String ref = "PAY-D5U-" + System.nanoTime();
        String providerRef = "PSP-D5U-" + System.nanoTime();
        OffsetDateTime now = OffsetDateTime.now();
        Payment p = new Payment(userId, ref, amount, null, now, now.plusMinutes(30));
        p.markPending(providerRef);
        paymentRepository.save(p);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, amount, "KGS", "TXN-D5U-" + System.nanoTime()));
        return ref;
    }

    private long ledgerCount(Long userId, DepositTransaction.TransactionType type) {
        return depositRepository.findTransactionsByUserId(userId).stream()
                .filter(tx -> tx.getType() == type).count();
    }

    private BigDecimal ledgerAmount(Long userId, DepositTransaction.TransactionType type) {
        return depositRepository.findTransactionsByUserId(userId).stream()
                .filter(tx -> tx.getType() == type).findFirst().orElseThrow().getAmount();
    }

    private Object awaitThenCallReal(org.mockito.invocation.InvocationOnMock inv, CountDownLatch latch) throws Throwable {
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("D5-U synchronization latch timed out - forced interleaving failed to occur");
        }
        return inv.callRealMethod();
    }

    private void runTwoThreads(String nameA, Runnable a, String nameB, Runnable b) throws InterruptedException {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Thread threadA = new Thread(() -> {
            try { barrier.await(WAIT_SECONDS, TimeUnit.SECONDS); a.run(); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, nameA);
        Thread threadB = new Thread(() -> {
            try { barrier.await(WAIT_SECONDS, TimeUnit.SECONDS); b.run(); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, nameB);
        threadA.start();
        threadB.start();
        threadA.join(JOIN_TIMEOUT_MS);
        threadB.join(JOIN_TIMEOUT_MS);
        assertThat(threadA.isAlive()).as("thread %s must have finished - no deadlock", nameA).isFalse();
        assertThat(threadB.isAlive()).as("thread %s must have finished - no deadlock", nameB).isFalse();
    }

    // ======================================================================
    // SCENARIO A — credit wins: unblock's stale write is rejected, and the WHOLE
    // resolveSaleFailure/failSale transaction rolls back atomically - SaleResolution, Auction,
    // Vehicle, FrozenDeposit deletion, and the UNBLOCK ledger row all revert together.
    // ======================================================================

    @RepeatedTest(5)
    @DisplayName("A: credit holds its pessimistic lock and commits while failSale's unblock is in progress -> " +
            "credit always succeeds, unblock's stale write is rejected by @Version, and the WHOLE sale-failure " +
            "resolution transaction (SaleResolution, Auction, Vehicle, FrozenDeposit, UNBLOCK ledger) rolls back atomically")
    void creditWins_WholeFailSaleTransactionRollsBack() throws InterruptedException {
        Fixture f = newWaitingForPaymentAuction();
        Long versionBeforeRace = depositRepository.findByUserId(f.winnerId()).orElseThrow().getVersion();
        long paymentCreditBaseline = ledgerCount(f.winnerId(), DepositTransaction.TransactionType.PAYMENT_CREDIT);
        long unblockBaseline = ledgerCount(f.winnerId(), DepositTransaction.TransactionType.UNBLOCK);
        String ref = newSucceedingPayment(f.winnerId(), CREDIT_AMOUNT);

        CountDownLatch creditCommitted = new CountDownLatch(1);
        // TEST-01: the other side must have READ the deposit before credit commits - otherwise a
        // fast credit (a warm JVM) commits first, the read is fresh, no stale write, and the test
        // failed whenever another class had warmed up the same code. Credit's write waits for it;
        // the read is a plain SELECT, never blocked by credit's FOR UPDATE lock, so no deadlock.
        CountDownLatch unblockRead = new CountDownLatch(1);

        // Only unblock-thread's save is gated on credit's full commit signal; unblock's own
        // unlocked findByUserId read is deliberately left un-gated - under PostgreSQL READ
        // COMMITTED it is never blocked by credit's uncommitted FOR UPDATE lock, so gating it
        // would misrepresent real Postgres semantics (same reasoning already validated in D5-D/E/F).
        doAnswer(inv -> {
            Object read = inv.callRealMethod();
            if (Thread.currentThread().getName().equals("unblock-thread")) {
                unblockRead.countDown();
            }
            return read;
        }).when(depositRepositorySpy).findByUserId(f.winnerId());

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("unblock-thread")) {
                return awaitThenCallReal(inv, creditCommitted);
            }
            if (Thread.currentThread().getName().equals("credit-thread")) {
                return awaitThenCallReal(inv, unblockRead);
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).save(any(Deposit.class));

        AtomicReference<Throwable> creditErr = new AtomicReference<>();
        AtomicReference<Throwable> unblockErr = new AtomicReference<>();

        runTwoThreads(
                "credit-thread", () -> {
                    try { orchestrator.confirmPayment(ref); }
                    catch (Throwable t) { creditErr.set(t); }
                    finally { creditCommitted.countDown(); }
                },
                "unblock-thread", () -> {
                    try {
                        saleAdministrationService.failSale(f.auctionId(), SaleResolutionResult.B, null,
                                "test sale failure", ADMIN_ID);
                    } catch (Throwable t) { unblockErr.set(t); }
                }
        );

        assertThat(creditErr.get()).as("credit must always succeed - it holds its pessimistic lock continuously").isNull();
        assertThat(unblockErr.get())
                .as("failSale's unblock must be rejected by the real @Version check - the exact wrapper " +
                        "hierarchy is Spring Data JPA's own translation of the real PostgreSQL/Hibernate optimistic " +
                        "conflict, not a manufactured exception")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // --- fresh reads only: unblock's whole sale-failure-resolution transaction rolled back ---
        Deposit afterRace = depositRepository.findByUserId(f.winnerId()).orElseThrow();
        assertThat(afterRace.getBalance())
                .as("only credit's mutation applied, unblock's was rejected")
                .isEqualByComparingTo(INITIAL_BALANCE.add(CREDIT_AMOUNT));
        assertThat(afterRace.getBlockedAmount())
                .as("unblock's mutation rolled back - still fully frozen")
                .isEqualByComparingTo(FREEZE_AMOUNT);
        assertThat(afterRace.getAvailableBalance())
                .isEqualByComparingTo(afterRace.getBalance().subtract(afterRace.getBlockedAmount()));
        assertThat(afterRace.getVersion()).as("only credit's mutation actually committed").isEqualTo(versionBeforeRace + 1);

        List<FrozenDeposit> frozen = depositRepository.getFrozenDepositsByDepositId(f.depositId());
        assertThat(frozen)
                .as("NO PARTIAL UNBLOCK: the failed transaction's FrozenDeposit DELETE rolled back - the row " +
                        "must still exist, still winner-flagged")
                .hasSize(1);
        assertThat(frozen.get(0).getAuctionId()).isEqualTo(f.auctionId());
        assertThat(frozen.get(0).getAmount()).isEqualByComparingTo(FREEZE_AMOUNT);
        assertThat(frozen.get(0).isWinner()).isTrue();

        assertThat(ledgerCount(f.winnerId(), DepositTransaction.TransactionType.PAYMENT_CREDIT))
                .isEqualTo(paymentCreditBaseline + 1);
        assertThat(ledgerAmount(f.winnerId(), DepositTransaction.TransactionType.PAYMENT_CREDIT))
                .isEqualByComparingTo(CREDIT_AMOUNT);
        assertThat(ledgerCount(f.winnerId(), DepositTransaction.TransactionType.UNBLOCK))
                .as("NO ORPHAN LEDGER ENTRY: the failed unblock's UNBLOCK row rolled back")
                .isEqualTo(unblockBaseline);

        // --- the differentiator from D5-A/D/E/F: the WHOLE resolveSaleFailure transaction, not
        // merely the Deposit mutation, must have rolled back ---
        assertThat(saleResolutionRepository.existsByAuctionId(f.auctionId()))
                .as("NO PARTIALLY-RESOLVED SALE FAILURE: the SaleResolution insert rolled back together " +
                        "with the failed Deposit mutation - it must not exist").isFalse();
        Auction auctionAfter = auctionRepository.findById(f.auctionId()).orElseThrow();
        assertThat(auctionAfter.getStatus())
                .as("Auction.markSaleFailed()'s persisted status transition rolled back - still WAITING_FOR_PAYMENT")
                .isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
        Vehicle vehicleAfter = vehicleRepository.findById(f.vehicleId()).orElseThrow();
        assertThat(vehicleAfter.getStatus())
                .as("Vehicle.returnToApproved()'s mutation rolled back - still IN_AUCTION")
                .isEqualTo(VehicleStatus.IN_AUCTION);
        // No PaymentEvidence exists in this fixture, so resolveSaleFailure never attempts to create
        // a PaymentRefundEvidence regardless of race outcome - confirmed absent either way, not
        // itself a rollback proof, only completeness.
        assertThat(paymentRefundEvidenceRepository.existsByAuctionId(f.auctionId())).isFalse();

        Payment freshPayment = paymentRepository.findByMerchantReference(ref).orElseThrow();
        assertThat(freshPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    // ======================================================================
    // SCENARIO B — failSale's unblock commits first: credit acquires fresh state afterward and
    // also succeeds. This ordering is a valid business scenario - creditFromPayment/confirmPayment
    // has zero reference to Auction/SaleResolution state (confirmed by source read), so a wallet
    // top-up payment may legitimately confirm at any time, independent of any auction's outcome.
    // ======================================================================

    @RepeatedTest(5)
    @DisplayName("B: failSale's unblock commits before credit acquires its lock -> both succeed, correct " +
            "combined final state across Deposit, FrozenDeposit, ledger, Auction, and Vehicle")
    void unblockWins_BothSucceed() throws InterruptedException {
        Fixture f = newWaitingForPaymentAuction();
        Long versionBeforeRace = depositRepository.findByUserId(f.winnerId()).orElseThrow().getVersion();
        String ref = newSucceedingPayment(f.winnerId(), CREDIT_AMOUNT);

        CountDownLatch unblockCommitted = new CountDownLatch(1);

        doAnswer(inv -> awaitThenCallReal(inv, unblockCommitted))
                .when(depositRepositorySpy).findByUserIdForUpdate(f.winnerId());

        AtomicReference<Throwable> creditErr = new AtomicReference<>();
        AtomicReference<Throwable> unblockErr = new AtomicReference<>();

        runTwoThreads(
                "credit-thread", () -> { try { orchestrator.confirmPayment(ref); } catch (Throwable t) { creditErr.set(t); } },
                "unblock-thread", () -> {
                    try {
                        saleAdministrationService.failSale(f.auctionId(), SaleResolutionResult.B, null,
                                "test sale failure", ADMIN_ID);
                    } catch (Throwable t) { unblockErr.set(t); }
                    finally { unblockCommitted.countDown(); }
                }
        );

        assertThat(unblockErr.get()).as("unblock (failSale) must succeed - nothing contends with it yet").isNull();
        assertThat(creditErr.get()).as("credit always re-locks fresh state - never conflicts").isNull();

        Deposit afterRace = depositRepository.findByUserId(f.winnerId()).orElseThrow();
        assertThat(afterRace.getBalance())
                .as("both mutations survive: initial + credit (unblock never touches balance)")
                .isEqualByComparingTo(INITIAL_BALANCE.add(CREDIT_AMOUNT));
        assertThat(afterRace.getBlockedAmount())
                .as("unblock released the full frozen amount")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(afterRace.getAvailableBalance()).isEqualByComparingTo(afterRace.getBalance());
        assertThat(afterRace.getVersion()).as("two successful mutations").isEqualTo(versionBeforeRace + 2);

        assertThat(depositRepository.getFrozenDepositsByDepositId(f.depositId()))
                .as("FrozenDeposit deleted by the successful unblock").isEmpty();

        assertThat(ledgerCount(f.winnerId(), DepositTransaction.TransactionType.UNBLOCK)).isEqualTo(1);
        assertThat(ledgerAmount(f.winnerId(), DepositTransaction.TransactionType.UNBLOCK)).isEqualByComparingTo(FREEZE_AMOUNT);
        assertThat(ledgerCount(f.winnerId(), DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualTo(1);
        assertThat(ledgerAmount(f.winnerId(), DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualByComparingTo(CREDIT_AMOUNT);

        assertThat(saleResolutionRepository.existsByAuctionId(f.auctionId())).isTrue();
        Auction auctionAfter = auctionRepository.findById(f.auctionId()).orElseThrow();
        assertThat(auctionAfter.getStatus()).isEqualTo(AuctionStatus.SALE_FAILED);
        Vehicle vehicleAfter = vehicleRepository.findById(f.vehicleId()).orElseThrow();
        assertThat(vehicleAfter.getStatus()).isEqualTo(VehicleStatus.APPROVED);
        assertThat(paymentRefundEvidenceRepository.existsByAuctionId(f.auctionId())).isFalse();

        Payment freshPayment = paymentRepository.findByMerchantReference(ref).orElseThrow();
        assertThat(freshPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }
}
