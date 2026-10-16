// payment/application/PaymentCreditVsAdminAdjustConcurrencyPgIntegrationTest.java
package com.example.autoauction.payment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.AdminAdjustmentReason;
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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * D5-F: real-PostgreSQL proof of the actual semantics between
 * {@link DepositService#creditFromPayment} (D3: pessimistic {@code SELECT ... FOR UPDATE} via
 * {@code findByUserIdForUpdate}, held continuously from read to write within ONE physical
 * transaction owned by {@link PaymentConfirmationService#confirmPayment}) and
 * {@link DepositService#adminAdjust} (unlocked {@code findByUserId} read, plain deferred
 * {@code save()} - confirmed by direct source read to be structurally identical in shape to
 * {@code withdraw}/{@code chargeForDiagnosticRequest}/{@code chargeSellerViolationPenalty}, every
 * prior D5-A/D5-D/D5-E already-proven-safe opponent).
 *
 * <p>Recon-confirmed: {@code adminAdjust} is a single flat {@code @Transactional} (REQUIRED) -
 * its ONLY production caller, {@code AdminDepositController.adjustDeposit}, is not
 * {@code @Transactional} and adds no business logic of its own, so calling {@code adminAdjust}
 * directly (as done here) exercises the real, outermost application-service transaction boundary,
 * exactly as the controller itself does. No {@code REQUIRES_NEW} and no independently-committing
 * side effect exists anywhere in the method - its idempotency-key checks, ledger INSERT
 * ({@code TransactionType.ADMIN_ADJUSTMENT}), and Deposit {@code save()} all share the one
 * transaction, so a failed Deposit write rolls back the ledger row too.
 *
 * <p>Because a plain {@code SELECT} under PostgreSQL READ COMMITTED is never blocked by another
 * transaction's uncommitted {@code SELECT ... FOR UPDATE}, {@code adminAdjust}'s unlocked read is
 * deliberately left un-gated in Scenario A (gating it would misrepresent real Postgres semantics,
 * per D5-F AUDIT/RECON section 7A) - only its final write ({@code save()}) is gated on credit's
 * actual physical commit, which is the only event that determines whether its optimistic
 * {@code @Version} check can succeed.
 *
 * <p>Both financial operations are driven through their real production entry points -
 * {@link PaymentConfirmationOrchestrator#confirmPayment} and {@link DepositService#adminAdjust}
 * (the latter IS the outermost application-service boundary; the controller above it adds no
 * transaction/business logic of its own) - never by calling any lower-level primitive directly.
 * All synchronization is via a {@code @SpyBean} around the real, Testcontainers-backed
 * {@link DepositRepository}, gating only TIMING via {@link CountDownLatch} - never faking
 * behaviour or throwing a manufactured exception.
 */
@DisplayName("D5-F: creditFromPayment vs adminAdjust — real-PostgreSQL concurrency proof")
class PaymentCreditVsAdminAdjustConcurrencyPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PaymentConfirmationOrchestrator orchestrator;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private FakePaymentProviderPort fakeProvider;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    @SpyBean private DepositRepository depositRepositorySpy;

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("50000.00");
    private static final BigDecimal ADMIN_ADJUSTMENT = new BigDecimal("3500.00");
    private static final BigDecimal CREDIT_AMOUNT = new BigDecimal("7000.00");
    private static final long WAIT_SECONDS = 5;
    private static final long JOIN_TIMEOUT_MS = 15_000;
    private static final Long ADMIN_ACTOR_ID = 999L;

    private Long newUserWithDeposit() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "d5f" + System.nanoTime(), "d5f" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, INITIAL_BALANCE);
        return userId;
    }

    private String newSucceedingPayment(Long userId, BigDecimal amount) {
        String ref = "PAY-D5F-" + System.nanoTime();
        String providerRef = "PSP-D5F-" + System.nanoTime();
        OffsetDateTime now = OffsetDateTime.now();
        Payment p = new Payment(userId, ref, amount, null, now, now.plusMinutes(30));
        p.markPending(providerRef);
        paymentRepository.save(p);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, amount, "KGS", "TXN-D5F-" + System.nanoTime()));
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

    private DepositTransaction ledgerEntry(Long userId, DepositTransaction.TransactionType type) {
        return depositRepository.findTransactionsByUserId(userId).stream()
                .filter(tx -> tx.getType() == type).findFirst().orElseThrow();
    }

    private Object awaitThenCallReal(org.mockito.invocation.InvocationOnMock inv, CountDownLatch latch) throws Throwable {
        if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("D5-F synchronization latch timed out - forced interleaving failed to occur");
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
    // credit commits first (or while adminAdjust is in progress - observationally identical,
    // see class Javadoc / D5-D/D5-E precedent) -> adminAdjust's stale write is rejected, and its
    // WHOLE transaction - including the ADMIN_ADJUSTMENT ledger INSERT - rolls back atomically.
    // ======================================================================

    @RepeatedTest(5)
    @DisplayName("credit holds its pessimistic lock and commits while adminAdjust is in progress -> " +
            "credit always succeeds, adminAdjust's stale write is rejected by @Version, and its WHOLE " +
            "transaction (including the ADMIN_ADJUSTMENT ledger row) rolls back atomically")
    void creditCommitsFirst_AdminAdjustStaleWriteRejected_WholeAdjustmentRollsBack() throws InterruptedException {
        Long userId = newUserWithDeposit();
        Long versionBeforeRace = depositRepository.findByUserId(userId).orElseThrow().getVersion();
        String ref = newSucceedingPayment(userId, CREDIT_AMOUNT);
        String adminReference = "ADMIN-D5F-" + System.nanoTime();
        String adminIdempotencyKey = "ADMIN-D5F-KEY-" + System.nanoTime();

        CountDownLatch creditCommitted = new CountDownLatch(1);
        // TEST-01: the other side must have READ the deposit before credit commits - otherwise a
        // fast credit (a warm JVM) commits first, the read is fresh, no stale write, and the test
        // failed whenever another class had warmed up the same code. Credit's write waits for it;
        // the read is a plain SELECT, never blocked by credit's FOR UPDATE lock, so no deadlock.
        CountDownLatch adminRead = new CountDownLatch(1);

        // adminAdjust's plain findByUserId is deliberately left un-gated here - under PostgreSQL
        // READ COMMITTED it is never blocked by credit's uncommitted FOR UPDATE lock, so gating it
        // would misrepresent real Postgres semantics (see class Javadoc). Only the admin thread's
        // final write is gated on credit's actual physical commit - that is the only event that
        // determines whether its optimistic @Version check can succeed.
        doAnswer(inv -> {
            Object read = inv.callRealMethod();
            if (Thread.currentThread().getName().equals("admin-thread")) {
                adminRead.countDown();
            }
            return read;
        }).when(depositRepositorySpy).findByUserId(userId);

        doAnswer(inv -> {
            if (Thread.currentThread().getName().equals("admin-thread")) {
                return awaitThenCallReal(inv, creditCommitted);
            }
            if (Thread.currentThread().getName().equals("credit-thread")) {
                return awaitThenCallReal(inv, adminRead);
            }
            return inv.callRealMethod();
        }).when(depositRepositorySpy).save(any(Deposit.class));

        AtomicReference<Throwable> creditErr = new AtomicReference<>();
        AtomicReference<Throwable> adminErr = new AtomicReference<>();

        runTwoThreads(
                "credit-thread", () -> {
                    try { orchestrator.confirmPayment(ref); }
                    catch (Throwable t) { creditErr.set(t); }
                    finally { creditCommitted.countDown(); }
                },
                "admin-thread", () -> {
                    try {
                        depositService.adminAdjust(userId, ADMIN_ADJUSTMENT, AdminAdjustmentReason.SUPPORT_CORRECTION,
                                adminReference, adminIdempotencyKey, ADMIN_ACTOR_ID);
                    } catch (Throwable t) { adminErr.set(t); }
                }
        );

        assertThat(creditErr.get()).as("credit must always succeed - it holds its pessimistic lock continuously").isNull();
        assertThat(adminErr.get())
                .as("adminAdjust's stale write must be rejected by the real @Version check - the exact wrapper " +
                        "hierarchy is Spring Data JPA's own translation of the real PostgreSQL/Hibernate optimistic " +
                        "conflict, not a manufactured exception")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // --- fresh reads only: adminAdjust's whole transaction rolled back, credit's survives ---
        Deposit afterRace = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(afterRace.getBalance())
                .as("NO LOST UPDATE / NO DOUBLE CREDIT: only credit's delta applied, adminAdjust's was rejected")
                .isEqualByComparingTo(INITIAL_BALANCE.add(CREDIT_AMOUNT));
        assertThat(afterRace.getBlockedAmount())
                .as("NO BLOCKED-AMOUNT CORRUPTION: neither operation ever touches blockedAmount")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(afterRace.getAvailableBalance())
                .isEqualByComparingTo(afterRace.getBalance().subtract(afterRace.getBlockedAmount()));
        assertThat(afterRace.getVersion()).as("only credit's mutation actually committed").isEqualTo(versionBeforeRace + 1);

        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualTo(1);
        assertThat(ledgerAmount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualByComparingTo(CREDIT_AMOUNT);
        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.ADMIN_ADJUSTMENT))
                .as("NO ORPHAN LEDGER ENTRY: adminAdjust's failed transaction left no ledger row - the INSERT " +
                        "rolled back together with the rejected Deposit UPDATE").isEqualTo(0);

        Payment freshPayment = paymentRepository.findByMerchantReference(ref).orElseThrow();
        assertThat(freshPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    // ======================================================================
    // adminAdjust fully commits before credit even attempts its pessimistic lock -> both survive,
    // applied sequentially, no lost update either way.
    // ======================================================================

    @RepeatedTest(5)
    @DisplayName("adminAdjust commits before credit acquires its lock -> both succeed, correct combined final state")
    void adminAdjustCommitsFirst_BothSucceed() throws InterruptedException {
        Long userId = newUserWithDeposit();
        Long versionBeforeRace = depositRepository.findByUserId(userId).orElseThrow().getVersion();
        String ref = newSucceedingPayment(userId, CREDIT_AMOUNT);
        String adminReference = "ADMIN-D5F-" + System.nanoTime();
        String adminIdempotencyKey = "ADMIN-D5F-KEY-" + System.nanoTime();

        CountDownLatch adminAdjustCommitted = new CountDownLatch(1);

        doAnswer(inv -> awaitThenCallReal(inv, adminAdjustCommitted))
                .when(depositRepositorySpy).findByUserIdForUpdate(userId);

        AtomicReference<Throwable> creditErr = new AtomicReference<>();
        AtomicReference<Throwable> adminErr = new AtomicReference<>();

        runTwoThreads(
                "credit-thread", () -> { try { orchestrator.confirmPayment(ref); } catch (Throwable t) { creditErr.set(t); } },
                "admin-thread", () -> {
                    try {
                        depositService.adminAdjust(userId, ADMIN_ADJUSTMENT, AdminAdjustmentReason.SUPPORT_CORRECTION,
                                adminReference, adminIdempotencyKey, ADMIN_ACTOR_ID);
                    } catch (Throwable t) { adminErr.set(t); }
                    finally { adminAdjustCommitted.countDown(); }
                }
        );

        assertThat(adminErr.get()).as("adminAdjust must succeed - nothing contends with it yet").isNull();
        assertThat(creditErr.get()).as("credit always re-locks fresh state - never conflicts").isNull();

        Deposit afterRace = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(afterRace.getBalance())
                .as("both mutations survive: initial + admin adjustment + credit")
                .isEqualByComparingTo(INITIAL_BALANCE.add(ADMIN_ADJUSTMENT).add(CREDIT_AMOUNT));
        assertThat(afterRace.getBlockedAmount())
                .as("blockedAmount unchanged by either operation")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(afterRace.getAvailableBalance())
                .isEqualByComparingTo(afterRace.getBalance().subtract(afterRace.getBlockedAmount()));
        assertThat(afterRace.getVersion()).as("two successful mutations").isEqualTo(versionBeforeRace + 2);

        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualTo(1);
        assertThat(ledgerAmount(userId, DepositTransaction.TransactionType.PAYMENT_CREDIT)).isEqualByComparingTo(CREDIT_AMOUNT);

        assertThat(ledgerCount(userId, DepositTransaction.TransactionType.ADMIN_ADJUSTMENT)).isEqualTo(1);
        DepositTransaction adminTx = ledgerEntry(userId, DepositTransaction.TransactionType.ADMIN_ADJUSTMENT);
        assertThat(adminTx.getAmount()).isEqualByComparingTo(ADMIN_ADJUSTMENT);
        assertThat(adminTx.getReference()).isEqualTo(adminReference);
        assertThat(adminTx.getIdempotencyKey()).isEqualTo(adminIdempotencyKey);
        assertThat(adminTx.getPerformedByAdminId()).isEqualTo(ADMIN_ACTOR_ID);
        assertThat(adminTx.getDescription()).isEqualTo(AdminAdjustmentReason.SUPPORT_CORRECTION.name());
        assertThat(adminTx.getBalanceBefore()).isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(adminTx.getBalanceAfter()).isEqualByComparingTo(INITIAL_BALANCE.add(ADMIN_ADJUSTMENT));

        Payment freshPayment = paymentRepository.findByMerchantReference(ref).orElseThrow();
        assertThat(freshPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }
}
