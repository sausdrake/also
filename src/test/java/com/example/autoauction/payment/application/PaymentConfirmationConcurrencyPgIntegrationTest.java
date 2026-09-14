package com.example.autoauction.payment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentEventType;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1.4 §J (P0 correctness requirement): real-PostgreSQL, real-thread proof that the canonical
 * confirmation operation is safe under concurrent invocation - mirroring this project's own
 * proven {@code AdminIdentityApprovalConcurrencyPgIntegrationTest}/
 * {@code SettlementConcurrencyPgIntegrationTest} two-thread/CyclicBarrier convention. Unlike the
 * identity-approval race, the loser here does NOT receive an exception - the canonical
 * operation's own "already terminal -> safe no-op" branch (locked by P1.3/P1.4) means both
 * callers observe a successful, consistent result; the invariant under test is that exactly ONE
 * of them actually performed the transition+credit.
 */
@DisplayName("P1.4 §J: payment confirmation real-PostgreSQL concurrency proof")
class PaymentConfirmationConcurrencyPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PaymentConfirmationOrchestrator orchestrator;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentEventRepository paymentEventRepository;
    @Autowired private FakePaymentProviderPort fakeProvider;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private Long newUserWithDeposit() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "pcc" + System.nanoTime(), "pcc" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, BigDecimal.ZERO);
        return userId;
    }

    private Payment newPendingPayment(Long userId, String merchantReference, String providerReference) {
        OffsetDateTime now = OffsetDateTime.now();
        Payment p = new Payment(userId, merchantReference, new BigDecimal("500.00"), null, now, now.plusMinutes(30));
        p.markPending(providerReference);
        return paymentRepository.save(p);
    }

    /** D3: same as {@link #newPendingPayment}, with an explicit amount - for tests needing two genuinely different amounts. */
    private Payment newPendingPaymentWithAmount(Long userId, String merchantReference, String providerReference, BigDecimal amount) {
        OffsetDateTime now = OffsetDateTime.now();
        Payment p = new Payment(userId, merchantReference, amount, null, now, now.plusMinutes(30));
        p.markPending(providerReference);
        return paymentRepository.save(p);
    }

    private long paymentCreditCount(Long userId) {
        return depositRepository.findTransactionsByUserId(userId).stream()
                .filter(tx -> tx.getType() == DepositTransaction.TransactionType.PAYMENT_CREDIT)
                .count();
    }

    private void runConcurrently(Runnable a, Runnable b) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        executor.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                a.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        executor.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                b.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }

    @RepeatedTest(5)
    @DisplayName("13/15/18. two simultaneous confirmations of the SAME payment (webhook-style + reconciliation-style, both through the same operation): exactly one transition, exactly one PAYMENT_CREDIT")
    void twoConcurrentConfirmations_ExactlyOneTransitionOneCredit() throws InterruptedException {
        Long userId = newUserWithDeposit();
        String ref = "PAY-CONC-" + System.nanoTime();
        String providerRef = "PSP-CONC-" + System.nanoTime();
        Payment p = newPendingPayment(userId, ref, providerRef);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, new BigDecimal("500.00"), "KGS", "TXN-" + System.nanoTime()));

        AtomicReference<Payment> resultWebhookStyle = new AtomicReference<>();
        AtomicReference<Payment> resultReconciliationStyle = new AtomicReference<>();
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        // Both callers invoke the exact same canonical operation - there is no separate
        // "webhook path" vs "reconciliation path" implementation to diverge between.
        runConcurrently(
                () -> { try { resultWebhookStyle.set(orchestrator.confirmPayment(ref)); }
                        catch (Throwable t) { errA.set(t); } },
                () -> { try { resultReconciliationStyle.set(orchestrator.confirmPayment(ref)); }
                        catch (Throwable t) { errB.set(t); } });

        assertThat(errA.get()).as("errA=%s", errA.get()).isNull();
        assertThat(errB.get()).as("errB=%s", errB.get()).isNull();
        assertThat(resultWebhookStyle.get().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(resultReconciliationStyle.get().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        assertThat(paymentCreditCount(userId)).as("exactly one PAYMENT_CREDIT despite two callers").isEqualTo(1);
        assertThat(paymentEventRepository.findByPaymentId(p.getId()))
                .as("exactly one SUCCEEDED event - the loser's call produced no event at all")
                .extracting(com.example.autoauction.payment.domain.PaymentEvent::getType)
                .containsExactly(PaymentEventType.SUCCEEDED);

        Payment fresh = paymentRepository.findById(p.getId()).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        // Exact credited amount under concurrency, not merely the count - the balance must
        // reflect precisely one 500.00 credit, never a double-credit of any amount.
        assertThat(depositRepository.findByUserId(userId).orElseThrow().getBalance())
                .as("exact balance after exactly one concurrent-safe credit")
                .isEqualByComparingTo("500.00");
    }

    @RepeatedTest(5)
    @DisplayName("D3: two DIFFERENT payments for the SAME user, confirmed truly concurrently - both credits " +
            "serialize on the Deposit row lock, final balance is the exact sum of both, exactly two " +
            "PAYMENT_CREDIT entries, neither credit lost, no deadlock")
    void twoConcurrentConfirmations_DifferentPaymentsSameUser_BothCreditsApplied_NoDeadlock() throws InterruptedException {
        Long userId = newUserWithDeposit();

        // Deliberately DIFFERENT requested amounts (not newPendingPayment's shared fixed 500.00)
        // so the final "sum of both" assertion below is non-trivial - it cannot pass via
        // accidental double-counting of one identical amount.
        String refA = "PAY-D3-A-" + System.nanoTime();
        String providerRefA = "PSP-D3-A-" + System.nanoTime();
        Payment paymentA = newPendingPaymentWithAmount(userId, refA, providerRefA, new BigDecimal("300.00"));
        fakeProvider.stubStatus(providerRefA, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, new BigDecimal("300.00"), "KGS", "TXN-D3-A-" + System.nanoTime()));

        String refB = "PAY-D3-B-" + System.nanoTime();
        String providerRefB = "PSP-D3-B-" + System.nanoTime();
        Payment paymentB = newPendingPaymentWithAmount(userId, refB, providerRefB, new BigDecimal("700.00"));
        fakeProvider.stubStatus(providerRefB, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, new BigDecimal("700.00"), "KGS", "TXN-D3-B-" + System.nanoTime()));

        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        // The CyclicBarrier releases both threads at the same instant; across @RepeatedTest(5)
        // runs, either confirmation may win the race for the Deposit row lock first - this
        // naturally exercises BOTH orderings (A-locks-first and B-locks-first) without needing a
        // second, artificially-ordered test. A hung/deadlocked pair would fail
        // awaitTermination(15s) below, which IS the deadlock proof (adversarial check E).
        runConcurrently(
                () -> { try { orchestrator.confirmPayment(refA); } catch (Throwable t) { errA.set(t); } },
                () -> { try { orchestrator.confirmPayment(refB); } catch (Throwable t) { errB.set(t); } });

        assertThat(errA.get()).as("errA=%s", errA.get()).isNull();
        assertThat(errB.get()).as("errB=%s", errB.get()).isNull();

        Payment freshA = paymentRepository.findById(paymentA.getId()).orElseThrow();
        Payment freshB = paymentRepository.findById(paymentB.getId()).orElseThrow();
        assertThat(freshA.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(freshB.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        assertThat(paymentCreditCount(userId)).as("exactly two PAYMENT_CREDIT ledger entries - one per payment").isEqualTo(2);
        assertThat(depositRepository.findByUserId(userId).orElseThrow().getBalance())
                .as("final balance is the exact sum of both credits - neither lost to a lost-update race")
                .isEqualByComparingTo("1000.00");
    }

    @RepeatedTest(3)
    @DisplayName("concurrent attempts around EXPIRED -> SUCCEEDED: exactly one late-success transition, exactly one credit")
    void concurrentExpiredToSucceeded_ExactlyOneLateSuccessCredit() throws InterruptedException {
        Long userId = newUserWithDeposit();
        String ref = "PAY-EXP-CONC-" + System.nanoTime();
        String providerRef = "PSP-EXP-CONC-" + System.nanoTime();
        Payment p = newPendingPayment(userId, ref, providerRef);
        p.markExpired();
        paymentRepository.save(p);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, new BigDecimal("500.00"), "KGS", "TXN-" + System.nanoTime()));

        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        runConcurrently(
                () -> { try { orchestrator.confirmPayment(ref); } catch (Throwable t) { errA.set(t); } },
                () -> { try { orchestrator.confirmPayment(ref); } catch (Throwable t) { errB.set(t); } });

        assertThat(errA.get()).isNull();
        assertThat(errB.get()).isNull();
        assertThat(paymentCreditCount(userId)).isEqualTo(1);
        Payment fresh = paymentRepository.findById(p.getId()).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(fresh.isLateSuccessAfterExpiry()).isTrue();
    }
}
