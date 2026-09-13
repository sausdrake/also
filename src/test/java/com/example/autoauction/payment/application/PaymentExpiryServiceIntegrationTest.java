package com.example.autoauction.payment.application;

import com.example.autoauction.deposit.application.DepositService;
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
 * P1.5 §27: {@link PaymentExpiryService} - domain-level transition coverage plus the
 * confirmation-vs-expiry real-PostgreSQL race (§7 of the P1.5 final architecture gate).
 */
@DisplayName("P1.5 §27: payment expiry sweep")
class PaymentExpiryServiceIntegrationTest extends AbstractPostgresIntegrationTest {

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
        User u = new User(null, "pex" + System.nanoTime(), "pex" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, BigDecimal.ZERO);
        return userId;
    }

    private Payment newPayment(Long userId, String ref) {
        OffsetDateTime now = OffsetDateTime.now();
        Payment p = new Payment(userId, ref, new BigDecimal("500.00"), null, now, now.plusMinutes(30));
        return paymentRepository.save(p);
    }

    /** Pushes a non-terminal payment's effective expiry into the past via the real domain operation. */
    private Payment forceIntoPast(Payment payment) {
        Payment fresh = paymentRepository.findById(payment.getId()).orElseThrow();
        fresh.shortenExpiryTo(OffsetDateTime.now().minusMinutes(1));
        return paymentRepository.save(fresh);
    }

    /**
     * Test-only: for a TERMINAL payment, {@code shortenExpiryTo} is deliberately rejected by the
     * domain (it is only meaningful while non-terminal) - so proving "a terminal payment with a
     * past expiresAt still never expires" requires reconstructing via the same constructor the
     * persistence mapper itself uses, bypassing no domain invariant that matters here (currency
     * is still validated).
     */
    private Payment forceIntoPastViaDirectSave(Payment payment) {
        Payment fresh = paymentRepository.findById(payment.getId()).orElseThrow();
        Payment rebuilt = new Payment(fresh.getId(), fresh.getUserId(), fresh.getMerchantReference(),
                fresh.getAmountRequested(), fresh.getAmountConfirmed(), fresh.getCurrency(), fresh.getStatus(),
                fresh.getProviderTransactionId(), fresh.getProviderReference(), fresh.getCreationIdempotencyKey(),
                fresh.isLateSuccessAfterExpiry(), fresh.getCreatedAt(), OffsetDateTime.now().minusMinutes(1), fresh.getUpdatedAt());
        return paymentRepository.save(rebuilt);
    }

    private void runConcurrently(Runnable a, Runnable b) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        executor.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS); a.run(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        executor.submit(() -> {
            try { barrier.await(5, TimeUnit.SECONDS); b.run(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("CREATED expires: status -> EXPIRED, PaymentEvent(EXPIRED, SCHEDULER) appended")
    void createdExpires() {
        Long userId = newUserWithDeposit();
        Payment p = newPayment(userId, "PAY-EXP-C-" + System.nanoTime());
        forceIntoPast(p);

        boolean expired = paymentExpiryService.expireOnePayment(p.getId());
        assertThat(expired).isTrue();

        Payment reloaded = paymentRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(paymentEventRepository.findByPaymentId(p.getId()))
                .extracting(e -> e.getType()).containsExactly(PaymentEventType.EXPIRED);
    }

    @Test
    @DisplayName("PENDING expires: status -> EXPIRED")
    void pendingExpires() {
        Long userId = newUserWithDeposit();
        Payment p = newPayment(userId, "PAY-EXP-P-" + System.nanoTime());
        p.markPending("PSP-EXP-" + System.nanoTime());
        paymentRepository.save(p);
        forceIntoPast(p);

        boolean expired = paymentExpiryService.expireOnePayment(p.getId());
        assertThat(expired).isTrue();
        assertThat(paymentRepository.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    @DisplayName("SUCCEEDED never expires, even if expiresAt is in the past")
    void succeededNeverExpires() {
        Long userId = newUserWithDeposit();
        Payment p = newPayment(userId, "PAY-EXP-S-" + System.nanoTime());
        p.markPending("PSP-S-" + System.nanoTime());
        p.markSucceeded("TX-S-" + System.nanoTime(), new BigDecimal("500.00"));
        paymentRepository.save(p);
        forceIntoPastViaDirectSave(p);

        boolean expired = paymentExpiryService.expireOnePayment(p.getId());
        assertThat(expired).isFalse();
        assertThat(paymentRepository.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("FAILED never expires")
    void failedNeverExpires() {
        Long userId = newUserWithDeposit();
        Payment p = newPayment(userId, "PAY-EXP-F-" + System.nanoTime());
        p.markFailed();
        paymentRepository.save(p);
        forceIntoPastViaDirectSave(p);

        boolean expired = paymentExpiryService.expireOnePayment(p.getId());
        assertThat(expired).isFalse();
        assertThat(paymentRepository.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("CANCELLED never expires")
    void cancelledNeverExpires() {
        Long userId = newUserWithDeposit();
        Payment p = newPayment(userId, "PAY-EXP-X-" + System.nanoTime());
        p.markCancelled();
        paymentRepository.save(p);
        forceIntoPastViaDirectSave(p);

        boolean expired = paymentExpiryService.expireOnePayment(p.getId());
        assertThat(expired).isFalse();
        assertThat(paymentRepository.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    @DisplayName("expiry uses expiresAt, not createdAt: a freshly-created payment with a past expiresAt expires even though createdAt is recent")
    void expiryUsesExpiresAt_notCreatedAt() {
        Long userId = newUserWithDeposit();
        OffsetDateTime now = OffsetDateTime.now();
        // createdAt is "now" (recent); expiresAt must still be >= createdAt at construction time
        // (domain invariant), so shorten it afterward exactly like a provider-shorter-TTL would.
        Payment p = new Payment(userId, "PAY-EXP-EA-" + System.nanoTime(), new BigDecimal("500.00"), null, now, now.plusMinutes(30));
        p = paymentRepository.save(p);
        forceIntoPast(p);

        boolean expired = paymentExpiryService.expireOnePayment(p.getId());
        assertThat(expired).isTrue();
    }

    @Test
    @DisplayName("expiry is idempotent: a second call on an already-EXPIRED payment is a safe no-op, no duplicate event")
    void expiryIsIdempotent() {
        Long userId = newUserWithDeposit();
        Payment p = newPayment(userId, "PAY-EXP-IDEM-" + System.nanoTime());
        forceIntoPast(p);

        assertThat(paymentExpiryService.expireOnePayment(p.getId())).isTrue();
        assertThat(paymentExpiryService.expireOnePayment(p.getId())).isFalse();

        assertThat(paymentEventRepository.findByPaymentId(p.getId())).hasSize(1);
    }

    @RepeatedTest(3)
    @DisplayName("confirmation vs expiry race: no deadlock; if confirmation wins (SUCCEEDED) expiry becomes a no-op; if expiry wins, late-success via P1.4's existing EXPIRED->SUCCEEDED path remains possible")
    void confirmationVsExpiryRace_noDeadlock_noIncorrectOverwrite() throws InterruptedException {
        Long userId = newUserWithDeposit();
        String ref = "PAY-EXP-CONF-RACE-" + System.nanoTime();
        String providerRef = "PSP-EXP-CONF-RACE-" + System.nanoTime();
        Payment p = newPayment(userId, ref);
        p.markPending(providerRef);
        paymentRepository.save(p);
        forceIntoPast(p);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, new BigDecimal("500.00"), "KGS", "TXN-" + System.nanoTime()));

        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        runConcurrently(
                () -> { try { paymentConfirmationOrchestrator.confirmPayment(ref); } catch (Throwable t) { errA.set(t); } },
                () -> { try { paymentExpiryService.expireOnePayment(p.getId()); } catch (Throwable t) { errB.set(t); } });

        assertThat(errA.get()).as("errA=%s", errA.get()).isNull();
        assertThat(errB.get()).as("errB=%s", errB.get()).isNull();

        // Whichever op won the lock first, the final status must be a legitimate outcome:
        // SUCCEEDED (confirmation won, or expiry won then a follow-up confirmation would still
        // reach late success - here only one confirmation call is made, so SUCCEEDED directly
        // means confirmation's own lock-acquisition won) or EXPIRED (expiry won; a payment that
        // is still EXPIRED here simply means confirmation's single call lost the race for the
        // lock and then observed EXPIRED as its pre-state with SUCCEEDED already applied by...
        // no - confirmPayment itself would still resolve SUCCEEDED if it wins momentarily after
        // expiry, per P1.4's own EXPIRED->SUCCEEDED branch. The only way this ends EXPIRED is if
        // confirmation's provider pull raced such that expiry committed AFTER confirmation had
        // already returned - impossible under the single Payment-row lock used by both. So the
        // only reachable terminal state here is SUCCEEDED.
        Payment reloaded = paymentRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }
}
