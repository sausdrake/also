package com.example.autoauction.payment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentAmountPolicy;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
import com.example.autoauction.payment.domain.port.PaymentProviderPort;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.payment.infrastructure.provider.FakePaymentProviderPort;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
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
 * P1.5 §26/§6/§13: real-PostgreSQL, real-thread proofs of the locked P1.5 concurrency invariants -
 * mirrors this project's own proven {@code PaymentConfirmationConcurrencyPgIntegrationTest}
 * (P1.4) two-thread/CyclicBarrier convention.
 */
@DisplayName("P1.5: payment creation - real-PostgreSQL concurrency proofs")
class PaymentCreationConcurrencyPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PaymentCreationService paymentCreationService;
    @Autowired private PaymentCreationOrchestrator paymentCreationOrchestrator;
    @Autowired private PaymentExpiryService paymentExpiryService;
    @Autowired private com.example.autoauction.payment.application.PaymentConfirmationOrchestrator paymentConfirmationOrchestrator;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentEventRepository paymentEventRepository;
    @Autowired private FakePaymentProviderPort fakeProvider;
    @Autowired private DepositService depositService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    @BeforeEach
    void resetProviderDefaultStub() {
        fakeProvider.clearDefaultStub();
    }

    private Long newUserWithDeposit() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "pccx" + System.nanoTime(), "pccx" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, BigDecimal.ZERO);
        return userId;
    }

    /** Seeds an already-SUCCEEDED payment so {@code calculateCapacityUsage} has a non-zero confirmed component. */
    private Payment newSucceededPayment(Long userId, BigDecimal confirmedAmount) {
        OffsetDateTime now = OffsetDateTime.now();
        Payment p = new Payment(userId, "PAY-SEED-" + System.nanoTime(), confirmedAmount, null, now, now.plusMinutes(30));
        p.markPending("PSP-SEED-" + System.nanoTime());
        p.markSucceeded("TX-SEED-" + System.nanoTime(), confirmedAmount);
        return paymentRepository.save(p);
    }

    /** Pushes a non-terminal payment's effective expiry into the past via the real domain operation. */
    private Payment forceIntoPast(Payment payment) {
        Payment fresh = paymentRepository.findById(payment.getId()).orElseThrow();
        fresh.shortenExpiryTo(OffsetDateTime.now().minusMinutes(1));
        return paymentRepository.save(fresh);
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

    @RepeatedTest(3)
    @DisplayName("same user, same idempotency key, same request, truly concurrent: exactly one Payment, one CREATED event")
    void sameUserSameKeyConcurrent_exactlyOnePayment() throws InterruptedException {
        Long userId = newUserWithDeposit();
        String key = "idem-race-" + System.nanoTime();

        AtomicReference<Payment> resultA = new AtomicReference<>();
        AtomicReference<Payment> resultB = new AtomicReference<>();
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        runConcurrently(
                () -> { try { resultA.set(paymentCreationService.createPayment(userId, new BigDecimal("1000"), key).payment()); }
                        catch (Throwable t) { errA.set(t); } },
                () -> { try { resultB.set(paymentCreationService.createPayment(userId, new BigDecimal("1000"), key).payment()); }
                        catch (Throwable t) { errB.set(t); } });

        assertThat(errA.get()).as("errA=%s", errA.get()).isNull();
        assertThat(errB.get()).as("errB=%s", errB.get()).isNull();
        assertThat(resultA.get().getId()).isEqualTo(resultB.get().getId());

        Payment winner = resultA.get();
        assertThat(paymentEventRepository.findByPaymentId(winner.getId())).hasSize(1);
    }

    @RepeatedTest(3)
    @DisplayName("same idempotency key, DIFFERENT users, truly concurrent (no shared lock): exactly one wins, the loser gets a conflict, never the other user's Payment")
    void sameKeyDifferentUsersRace_exactlyOneWinsLoserConflicts() throws InterruptedException {
        Long userA = newUserWithDeposit();
        Long userB = newUserWithDeposit();
        String key = "idem-cross-race-" + System.nanoTime();
        // Both users' initiations are ambiguous (no stub) - irrelevant to this test, which is
        // exercising the CREATION-side race, not provider behaviour; the orchestrator is used
        // (rather than the service directly) specifically because ONLY the orchestrator catches
        // DataIntegrityViolationException and resolves it in a fresh transaction - see
        // PaymentCreationService.resolveConflictAfterRace's Javadoc.

        AtomicReference<Payment> resultA = new AtomicReference<>();
        AtomicReference<Payment> resultB = new AtomicReference<>();
        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        runConcurrently(
                () -> { try { resultA.set(paymentCreationOrchestrator.createPayment(userA, new BigDecimal("1000"), key).payment()); }
                        catch (Throwable t) { errA.set(t); } },
                () -> { try { resultB.set(paymentCreationOrchestrator.createPayment(userB, new BigDecimal("1000"), key).payment()); }
                        catch (Throwable t) { errB.set(t); } });

        boolean aSucceeded = errA.get() == null;
        boolean bSucceeded = errB.get() == null;
        assertThat(aSucceeded ^ bSucceeded).as("exactly one of the two concurrent requests succeeds").isTrue();

        Throwable loserError = aSucceeded ? errB.get() : errA.get();
        assertThat(loserError)
                .as("loser must get a conflict, never silently succeed or crash with a leaked persistence exception")
                .isInstanceOf(IllegalStateException.class);

        Payment winner = aSucceeded ? resultA.get() : resultB.get();
        Long winnerUserId = aSucceeded ? userA : userB;
        assertThat(winner.getUserId()).isEqualTo(winnerUserId);
        assertThat(paymentRepository.findByCreationIdempotencyKey(key)).get()
                .extracting(Payment::getId).isEqualTo(winner.getId());
    }

    @RepeatedTest(3)
    @DisplayName("different keys, daily limit race: existing confirmed 900,000 + two concurrent 100,000 requests -> only one accepted, no bypass")
    void dailyLimitRace_onlyCapacityFittingAccepted() throws InterruptedException {
        Long userId = newUserWithDeposit();
        newSucceededPayment(userId, new BigDecimal("900000.00"));

        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();
        AtomicReference<Payment> resultA = new AtomicReference<>();
        AtomicReference<Payment> resultB = new AtomicReference<>();

        runConcurrently(
                () -> { try { resultA.set(paymentCreationService.createPayment(userId, new BigDecimal("100000"), null).payment()); }
                        catch (Throwable t) { errA.set(t); } },
                () -> { try { resultB.set(paymentCreationService.createPayment(userId, new BigDecimal("100000"), null).payment()); }
                        catch (Throwable t) { errB.set(t); } });

        boolean aSucceeded = errA.get() == null;
        boolean bSucceeded = errB.get() == null;
        assertThat(aSucceeded ^ bSucceeded).as("exactly one of the two concurrent 100,000 requests is accepted").isTrue();
        Throwable loserError = aSucceeded ? errB.get() : errA.get();
        assertThat(loserError).isInstanceOf(IllegalStateException.class);

        BigDecimal finalUsage = paymentRepository.calculateCapacityUsage(userId, OffsetDateTime.now().minusHours(24));
        assertThat(finalUsage).as("total usage must never exceed the daily limit").isEqualByComparingTo("1000000.00");
    }

    @Test
    @DisplayName("different users' concurrent creations do not block each other - no global/shared lock")
    void differentUsers_doNotBlockEachOther() throws InterruptedException {
        Long userA = newUserWithDeposit();
        Long userB = newUserWithDeposit();

        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        runConcurrently(
                () -> { try { paymentCreationService.createPayment(userA, new BigDecimal("1000"), null); }
                        catch (Throwable t) { errA.set(t); } },
                () -> { try { paymentCreationService.createPayment(userB, new BigDecimal("1000"), null); }
                        catch (Throwable t) { errB.set(t); } });

        assertThat(errA.get()).isNull();
        assertThat(errB.get()).isNull();
    }

    @Test
    @DisplayName("reservation release via expiry: capacity unavailable while reserved, becomes available after expiry")
    void reservationRelease_viaExpiry_freesCapacity() {
        Long userId = newUserWithDeposit();
        newSucceededPayment(userId, new BigDecimal("950000.00"));
        Payment reserved = paymentCreationService.createPayment(userId, new BigDecimal("50000"), null).payment();

        assertThatCapacityRejects(userId, "100");

        forceIntoPast(reserved);
        boolean expired = paymentExpiryService.expireOnePayment(reserved.getId());
        assertThat(expired).isTrue();

        Payment afterExpiry = paymentCreationService.createPayment(userId, new BigDecimal("50000"), null).payment();
        assertThat(afterExpiry).isNotNull();
    }

    private void assertThatCapacityRejects(Long userId, String amount) {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> paymentCreationService.createPayment(userId, new BigDecimal(amount), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @RepeatedTest(3)
    @DisplayName("creation vs expiry concurrency: no deadlock, final usage never exceeds the daily limit")
    void creationVsExpiry_noFinancialInvariantViolation() throws InterruptedException {
        Long userId = newUserWithDeposit();
        newSucceededPayment(userId, new BigDecimal("950000.00"));
        Payment reserved = paymentCreationService.createPayment(userId, new BigDecimal("50000"), null).payment();
        forceIntoPast(reserved);

        AtomicReference<Throwable> creationError = new AtomicReference<>();

        runConcurrently(
                () -> { try { paymentCreationService.createPayment(userId, new BigDecimal("50000"), null); }
                        catch (Throwable t) { creationError.set(t); } },
                () -> paymentExpiryService.expireOnePayment(reserved.getId()));

        // Whichever order won (temporary conservative rejection is acceptable - see the P1.5
        // architecture gate §6) the invariant itself must never be violated.
        BigDecimal finalUsage = paymentRepository.calculateCapacityUsage(userId, OffsetDateTime.now().minusHours(24));
        assertThat(finalUsage).isLessThanOrEqualTo(PaymentAmountPolicy.DAILY_LIMIT);
    }

    @RepeatedTest(3)
    @DisplayName("creation vs confirmation concurrency: the combined atomic query never undercounts total usage")
    void creationVsConfirmation_combinedQueryNeverUndercounts() throws InterruptedException {
        Long userId = newUserWithDeposit();
        newSucceededPayment(userId, new BigDecimal("950000.00"));
        Payment pending = paymentCreationService.createPayment(userId, new BigDecimal("50000"), null).payment();
        String providerRef = "PSP-RACE-" + System.nanoTime();
        paymentCreationService.finalizeAccepted(pending.getId(), providerRef);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, new BigDecimal("50000"), "KGS", "TXN-" + System.nanoTime()));

        AtomicReference<Throwable> creationError = new AtomicReference<>();

        runConcurrently(
                () -> { try { paymentCreationService.createPayment(userId, new BigDecimal("50000"), null); }
                        catch (Throwable t) { creationError.set(t); } },
                () -> paymentConfirmationOrchestrator.confirmPayment(pending.getMerchantReference()));

        // Total usage (950k confirmed + 50k reserved-or-confirmed) is already exactly at the
        // limit regardless of which side's snapshot won the race - a NEW 50k request must ALWAYS
        // be rejected. A split (non-atomic) capacity read could undercount here and let it
        // through - the exact bug class proven and fixed during the P1.5 architecture challenge.
        assertThat(creationError.get())
                .as("must never be silently accepted due to a stale/split capacity read")
                .isInstanceOf(IllegalStateException.class);
    }
}
