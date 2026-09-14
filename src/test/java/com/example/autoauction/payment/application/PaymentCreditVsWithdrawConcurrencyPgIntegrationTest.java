// payment/application/PaymentCreditVsWithdrawConcurrencyPgIntegrationTest.java
package com.example.autoauction.payment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.port.PaymentProviderPort;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.payment.infrastructure.provider.FakePaymentProviderPort;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * D5-A: real-PostgreSQL proof of the actual semantics between
 * {@link DepositService#creditFromPayment} (D3: pessimistic {@code SELECT ... FOR UPDATE} via
 * {@code findByUserIdForUpdate}, held continuously from read to write within ONE physical
 * transaction owned by {@link PaymentConfirmationService#confirmPayment}) and
 * {@link DepositService#withdraw} (unlocked read, plain deferred {@code save()}, its own
 * standalone top-level transaction).
 *
 * <p>This pairing is structurally different from every prior D4/D5-M/D5-J finding: those all
 * involved a Deposit mutator that itself uses an unlocked-read-then-deferred-write shape (either
 * side of the race optimistic). Here, {@code creditFromPayment} holds a genuine pessimistic lock
 * continuously from its very first statement to its own write - it can therefore never itself lose
 * an optimistic race (its own {@code save()} always operates on a version it just read under lock,
 * with no other transaction able to have changed the row in between). The only question worth
 * proving empirically is whether {@code withdraw} - the pure optimistic side - is correctly
 * rejected (never silently lost, never allowed to corrupt {@code blockedAmount}, never left as an
 * orphan ledger row) regardless of which side reads or attempts to write first, and that BOTH
 * mutations survive when they are genuinely sequential (no lock contention window at all).
 *
 * <p>{@code creditFromPayment} is driven through its real, production entry point -
 * {@link PaymentConfirmationOrchestrator#confirmPayment} - exactly like every other
 * credit-concurrency test in this project ({@code PaymentConfirmationConcurrencyPgIntegrationTest},
 * {@code DepositCreditVsMutatorsConcurrencyPgIntegrationTest}), never by calling
 * {@code DepositService.creditFromPayment} directly - this exercises the full real Payment
 * lock/status-transition/amount-match/ledger chain, not a bypassed shortcut.
 *
 * <p>All synchronization is via a {@code @SpyBean} around the real, Testcontainers-backed
 * {@link DepositRepository}, gating only TIMING via {@link CountDownLatch} - never faking behaviour
 * or throwing a manufactured exception; every SQL statement executes for real against real
 * PostgreSQL.
 */
@DisplayName("D5-A: creditFromPayment vs withdraw — real-PostgreSQL concurrency proof")
class PaymentCreditVsWithdrawConcurrencyPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PaymentConfirmationOrchestrator orchestrator;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private FakePaymentProviderPort fakeProvider;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private com.example.autoauction.auction.domain.port.AuctionRepository auctionRepository;

    @SpyBean private DepositRepository depositRepositorySpy;

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("50000.00");
    private static final BigDecimal FREEZE_AMOUNT = new BigDecimal("10000.00");
    private static final BigDecimal WITHDRAW_AMOUNT = new BigDecimal("15000.00");
    private static final BigDecimal CREDIT_AMOUNT = new BigDecimal("7000.00");
    private static final long WAIT_SECONDS = 5;
    private static final long JOIN_TIMEOUT_MS = 15_000;

    private Long newUserWithDeposit() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "d5a" + System.nanoTime(), "d5a" + System.nanoTime() + "@test.com",
                "encoded", true, java.util.Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, INITIAL_BALANCE);
        return userId;
    }

    private String newSucceedingPayment(Long userId, BigDecimal amount) {
        String ref = "PAY-D5A-" + System.nanoTime();
        String providerRef = "PSP-D5A-" + System.nanoTime();
        OffsetDateTime now = OffsetDateTime.now();
        Payment p = new Payment(userId, ref, amount, null, now, now.plusMinutes(30));
        p.markPending(providerRef);
        paymentRepository.save(p);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, amount, "KGS", "TXN-D5A-" + System.nanoTime()));
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
            throw new AssertionError("D5-A synchronization latch timed out - forced interleaving failed to occur");
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
    // SCENARIOS 1+2 (structurally identical - see class Javadoc): credit's pessimistic lock
    // guarantees it always commits successfully; withdraw's stale read/write is correctly
    // rejected whenever its own write attempt lands after credit's commit, regardless of
    // whether withdraw's READ happened before or during credit's transaction (a plain read never
    // blocks on FOR UPDATE, so this ordering distinction has zero effect on the outcome - proven
    // once here rather than duplicated as two near-identical tests).
    // ======================================================================

    @RepeatedTest(5)
    @DisplayName("credit holds its pessimistic lock and commits while withdraw is in progress -> " +
            "credit always succeeds cleanly, withdraw's stale write is correctly rejected by @Version, never silently lost")
    void creditCommitsFirst_WithdrawStaleWriteRejected() throws InterruptedException {
        Long userId = newUserWithDeposit();
        depositService.freezeForAuction(userId, newAuctionIdSurrogate(userId), FREEZE_AMOUNT);
        Long versionBeforeRace = depositRepository.findByUserId(userId).orElseThrow().getVersion();
        String ref = newSucceedingPayment(userId, CREDIT_AMOUNT);

        CountDownLatch creditLockAcquired = new CountDownLatch(1);
        CountDownLatch creditCommitted = new CountDownLatch(1);

        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            creditLockAcquired.countDown();
            return result;
        }).when(depositRepositorySpy).findByUserIdForUpdate(userId);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("withdraw-thread")) {
                return awaitThenCallReal(inv, creditLockAcquired);
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).findByUserId(userId);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("withdraw-thread")) {
                return awaitThenCallReal(inv, creditCommitted);
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).save(any(Deposit.class));

        AtomicReference<Throwable> creditErr = new AtomicReference<>();
        AtomicReference<Throwable> withdrawErr = new AtomicReference<>();

        runTwoThreads(
                "credit-thread", () -> {
                    try { orchestrator.confirmPayment(ref); }
                    catch (Throwable t) { creditErr.set(t); }
                    finally { creditCommitted.countDown(); }
                },
                "withdraw-thread", () -> { try { depositService.withdraw(userId, WITHDRAW_AMOUNT, "D5A-WITHDRAW-" + System.nanoTime()); } catch (Throwable t) { withdrawErr.set(t); } }
        );

        assertThat(creditErr.get()).as("credit must always succeed - it holds its pessimistic lock continuously").isNull();
        assertThat(withdrawErr.get())
                .as("withdraw's stale write must be rejected by the real @Version check - never silently lost, " +
                        "never allowed to overwrite the credited balance")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // --- fresh reads only: no lost update, no double credit, no blockedAmount corruption ---
        Deposit afterRace = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(afterRace.getBalance())
                .as("NO LOST UPDATE / NO DOUBLE CREDIT: only credit's delta applied, withdraw's was rejected")
                .isEqualByComparingTo(INITIAL_BALANCE.add(CREDIT_AMOUNT));
        assertThat(afterRace.getBlockedAmount())
                .as("NO BLOCKED-AMOUNT CORRUPTION: neither operation ever touches blockedAmount")
                .isEqualByComparingTo(FREEZE_AMOUNT);
        assertThat(afterRace.getVersion()).as("only credit's mutation actually committed").isEqualTo(versionBeforeRace + 1);

        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualTo(1);
        assertThat(ledgerAmount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualByComparingTo(CREDIT_AMOUNT);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.WITHDRAWAL))
                .as("NO ORPHAN LEDGER ENTRY: withdraw's failed transaction left no ledger row").isEqualTo(0);

        Payment freshPayment = paymentRepository.findByMerchantReference(ref).orElseThrow();
        assertThat(freshPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    // ======================================================================
    // SCENARIO 3: withdraw fully commits before credit even attempts its pessimistic lock ->
    // both mutations survive, applied sequentially on top of each other, no lost update either way.
    // ======================================================================

    @RepeatedTest(5)
    @DisplayName("withdraw commits before credit acquires its lock -> both succeed, correct combined final state")
    void withdrawCommitsFirst_BothSucceed() throws InterruptedException {
        Long userId = newUserWithDeposit();
        depositService.freezeForAuction(userId, newAuctionIdSurrogate(userId), FREEZE_AMOUNT);
        Long versionBeforeRace = depositRepository.findByUserId(userId).orElseThrow().getVersion();
        String ref = newSucceedingPayment(userId, CREDIT_AMOUNT);

        CountDownLatch withdrawCommitted = new CountDownLatch(1);

        doAnswer(inv -> awaitThenCallReal(inv, withdrawCommitted))
                .when(depositRepositorySpy).findByUserIdForUpdate(userId);

        AtomicReference<Throwable> creditErr = new AtomicReference<>();
        AtomicReference<Throwable> withdrawErr = new AtomicReference<>();

        runTwoThreads(
                "credit-thread", () -> { try { orchestrator.confirmPayment(ref); } catch (Throwable t) { creditErr.set(t); } },
                "withdraw-thread", () -> {
                    try { depositService.withdraw(userId, WITHDRAW_AMOUNT, "D5A-WITHDRAW-" + System.nanoTime()); }
                    catch (Throwable t) { withdrawErr.set(t); }
                    finally { withdrawCommitted.countDown(); }
                }
        );

        assertThat(withdrawErr.get()).as("withdraw must succeed - nothing contends with it yet").isNull();
        assertThat(creditErr.get()).as("credit always re-locks fresh state - never conflicts").isNull();

        Deposit afterRace = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(afterRace.getBalance())
                .as("both mutations survive: initial - withdrawal + credit")
                .isEqualByComparingTo(INITIAL_BALANCE.subtract(WITHDRAW_AMOUNT).add(CREDIT_AMOUNT));
        assertThat(afterRace.getBlockedAmount()).as("blockedAmount unchanged by either operation").isEqualByComparingTo(FREEZE_AMOUNT);
        assertThat(afterRace.getVersion()).as("two successful mutations").isEqualTo(versionBeforeRace + 2);

        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.WITHDRAWAL)).isEqualTo(1);
        assertThat(ledgerAmount(userId, DepositTransaction.TransactionType.WITHDRAWAL)).isEqualByComparingTo(WITHDRAW_AMOUNT);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualTo(1);
        assertThat(ledgerAmount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualByComparingTo(CREDIT_AMOUNT);

        Payment freshPayment = paymentRepository.findByMerchantReference(ref).orElseThrow();
        assertThat(freshPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    /**
     * freezeForAuction requires a real Auction row (deposit_frozen.auction_id is a genuine FK) -
     * this test only needs SOME distinct, valid auction id to establish a non-zero blockedAmount
     * fixture; it does not exercise any auction-specific behaviour itself.
     */
    private Long newAuctionIdSurrogate(Long userId) {
        return auctionRepository.save(new com.example.autoauction.auction.domain.Auction(
                1L, new BigDecimal("1000000"), null, null, new BigDecimal("10000"),
                OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(1), userId)).getId();
    }
}
