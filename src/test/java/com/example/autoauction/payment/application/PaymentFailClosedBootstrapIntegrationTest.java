// payment/application/PaymentFailClosedBootstrapIntegrationTest.java
package com.example.autoauction.payment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentEventType;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.payment.infrastructure.provider.PaymentProviderUnavailableException;
import com.example.autoauction.payment.infrastructure.provider.UnavailablePaymentProviderPort;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fail-closed bootstrap slice - Tests C, D, E: end-to-end proof, against real PostgreSQL, that the
 * production payment funnel behaves exactly as the approved plan requires when NO real
 * bank/provider is configured.
 *
 * <p><b>Why this class does not extend {@code AbstractPostgresIntegrationTest} with its default
 * profiles.</b> Every other Postgres integration test in this project activates the {@code "test"}
 * profile alongside {@code "pg"}, which resolves {@code FakePaymentProviderPort} - exactly the
 * bean this slice must NOT exercise here. This class instead overrides the active profile set to
 * {@code "pg"} only ({@code inheritProfiles = false}, since {@code @ActiveProfiles} merges with an
 * inherited declaration by default), so the real, production {@link UnavailablePaymentProviderPort}
 * bean (the only non-{@code test} {@code PaymentProviderPort} candidate) is the one actually
 * wired into {@link PaymentCreationOrchestrator}. It still reuses
 * {@link AbstractPostgresIntegrationTest}'s {@code @Testcontainers}/{@code @ServiceConnection}
 * real-Postgres container field, so no new container/database wiring is introduced.
 *
 * <p><b>Why {@code r2.*} properties are supplied inline.</b> Dropping the {@code "test"} profile
 * also flips {@code FakeStorageClient} to the real {@code R2StorageClient}, whose constructor
 * requires four {@code @Value} properties with no defaults ({@code r2.account-id/bucket/
 * access-key-id/secret-access-key}, confirmed by direct source read - none are set in this
 * environment). These dummy values exist ONLY to let this test's Spring context construct that
 * unrelated bean at all; nothing in this test ever exercises storage. This is the "narrowest
 * legitimate Spring context" adjustment permitted by the approved plan, not a change to any shared
 * configuration file - {@code application.properties}/{@code application-test.properties} remain
 * untouched.
 */
@SpringBootTest(properties = {
        "r2.account-id=fail-closed-bootstrap-test-unused",
        "r2.bucket=fail-closed-bootstrap-test-unused",
        "r2.access-key-id=fail-closed-bootstrap-test-unused",
        "r2.secret-access-key=fail-closed-bootstrap-test-unused"
})
@ActiveProfiles(profiles = "pg", inheritProfiles = false)
@DisplayName("Payment Fail-Closed Bootstrap: real PostgreSQL, non-test profile, no real provider configured")
class PaymentFailClosedBootstrapIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PaymentCreationOrchestrator paymentCreationOrchestrator;
    @Autowired private PaymentConfirmationOrchestrator paymentConfirmationOrchestrator;
    @Autowired private PaymentExpiryService paymentExpiryService;
    @Autowired private com.example.autoauction.payment.domain.port.PaymentProviderPort paymentProviderPort;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentEventRepository paymentEventRepository;
    @Autowired private DepositRepository depositRepository;
    @Autowired private DepositService depositService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("50000.00");
    private static final BigDecimal TOP_UP_AMOUNT = new BigDecimal("10000.00");

    private Long newUserWithDeposit() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "pfc" + System.nanoTime(), "pfc" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, INITIAL_BALANCE);
        return userId;
    }

    @Test
    @DisplayName("the wired PaymentProviderPort is the fail-closed adapter, not the test fake")
    void wiredProviderIsFailClosed() {
        assertThat(paymentProviderPort).isInstanceOf(UnavailablePaymentProviderPort.class);
    }

    // ======================================================================
    // TEST C + D + Attacks 1, 4 — real PaymentCreationOrchestrator.createPayment(...), fail-closed
    // provider active: Payment remains CREATED, no providerReference, no Deposit credit.
    // ======================================================================

    @Test
    @DisplayName("C/D: createPayment with the fail-closed provider active -> Payment stays CREATED, " +
            "no providerReference, CREATED event recorded, no Deposit credit occurs")
    void createPayment_failClosedProvider_staysCreated_noCredit() {
        Long userId = newUserWithDeposit();
        Deposit before = depositRepository.findByUserId(userId).orElseThrow();

        PaymentCreationView view = paymentCreationOrchestrator.createPayment(userId, TOP_UP_AMOUNT, null);

        Payment payment = view.payment();
        assertThat(payment.getId()).isNotNull();
        assertThat(payment.getStatus()).as("provider-unavailable initiation must never reach PENDING/FAILED - " +
                "only CREATED, per the locked ambiguous-outcome rule").isEqualTo(PaymentStatus.CREATED);
        assertThat(payment.getProviderReference()).as("no providerReference can exist without a provider " +
                "having actually accepted the request").isNull();
        assertThat(payment.getAmountConfirmed()).isNull();
        assertThat(payment.getProviderTransactionId()).isNull();
        assertThat(view.checkoutUrl()).isNull();
        assertThat(view.qrPayload()).isNull();

        // --- fresh reads only ---
        Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.CREATED);

        assertThat(paymentEventRepository.findByPaymentId(payment.getId()))
                .as("CREATED + INITIATION_ATTEMPTED only - the pre-provider-call checkpoint is recorded, " +
                        "but no PENDING/SUCCEEDED/FAILED event was ever appended")
                .extracting(e -> e.getType())
                .containsExactly(PaymentEventType.CREATED, PaymentEventType.INITIATION_ATTEMPTED);

        Deposit after = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(after.getBalance()).as("no deposit credit occurred").isEqualByComparingTo(before.getBalance());
        assertThat(after.getVersion()).as("no Deposit mutation at all - version unchanged").isEqualTo(before.getVersion());

        List<DepositTransaction> paymentCreditLedgerRows = depositRepository.findTransactionsByUserId(userId).stream()
                .filter(tx -> tx.getType() == DepositTransaction.TransactionType.PAYMENT_CREDIT)
                .toList();
        assertThat(paymentCreditLedgerRows)
                .as("no PAYMENT_CREDIT ledger row was created by this payment attempt " +
                        "(the fixture's own initial-balance DEPOSIT row is unrelated and expected)")
                .isEmpty();
    }

    // ======================================================================
    // Attack 1 — arbitrary client amount/idempotency key still only ever reaches CREATED.
    // ======================================================================

    @Test
    @DisplayName("Attack 1: arbitrary client-chosen amount/idempotencyKey still only ever reaches CREATED")
    void attack1_arbitraryClientInput_neverReachesSucceeded() {
        Long userId = newUserWithDeposit();
        String clientChosenKey = "client-supplied-idempotency-key-" + System.nanoTime();

        PaymentCreationView view = paymentCreationOrchestrator.createPayment(
                userId, new BigDecimal("123456.78").min(new BigDecimal("199999.99")), clientChosenKey);

        assertThat(view.payment().getStatus()).isEqualTo(PaymentStatus.CREATED);
    }

    // ======================================================================
    // Attack 3 / Section 6 — the fail-closed provider, asked directly for status, cannot return
    // SUCCEEDED, even for a reference that happens to coincide with a real payment's merchantReference.
    // ======================================================================

    @Test
    @DisplayName("Attack 3: getPaymentStatus on the fail-closed provider never returns SUCCEEDED, " +
            "even when asked about a real, just-created payment's own reference")
    void attack3_providerStatusPull_neverSucceeds() {
        Long userId = newUserWithDeposit();
        PaymentCreationView view = paymentCreationOrchestrator.createPayment(userId, TOP_UP_AMOUNT, null);

        var status = paymentProviderPort.getPaymentStatus(view.payment().getMerchantReference());
        assertThat(status.state())
                .isNotEqualTo(com.example.autoauction.payment.domain.port.PaymentProviderPort.ProviderPaymentState.SUCCEEDED);
    }

    // ======================================================================
    // Attack 5 — confirmation called against a CREATED payment (no providerReference) remains the
    // existing, already-built safe no-op. No new trigger is introduced; this proves the EXISTING
    // logic, unmodified, is what makes this safe.
    // ======================================================================

    @Test
    @DisplayName("Attack 5: confirmPayment against a fail-closed-created (CREATED, no providerReference) " +
            "payment is a safe no-op - unchanged, pre-existing behaviour")
    void attack5_confirmationOnCreatedPayment_isSafeNoOp() {
        Long userId = newUserWithDeposit();
        Deposit before = depositRepository.findByUserId(userId).orElseThrow();
        PaymentCreationView view = paymentCreationOrchestrator.createPayment(userId, TOP_UP_AMOUNT, null);

        Payment result = paymentConfirmationOrchestrator.confirmPayment(view.payment().getMerchantReference());

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CREATED);
        Deposit after = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(after.getBalance()).isEqualByComparingTo(before.getBalance());
        assertThat(paymentEventRepository.findByPaymentId(view.payment().getId()))
                .extracting(e -> e.getType())
                .containsExactly(PaymentEventType.CREATED, PaymentEventType.INITIATION_ATTEMPTED);
    }

    // ======================================================================
    // TEST E / Attack 6 — the CREATED payment eventually expires via the existing, unmodified
    // expiry service, using the repository's own established time-manipulation convention
    // (shortenExpiryTo, the real domain operation) - never Thread.sleep, never a mocked Clock.
    // ======================================================================

    @Test
    @DisplayName("E: a fail-closed-created CREATED payment expires via the existing PaymentExpiryService " +
            "-> EXPIRED, PaymentEvent(EXPIRED, SCHEDULER), never SUCCEEDED, no deposit credit")
    void expiry_fromFailClosedCreated_reachesExpired_notSucceeded() {
        Long userId = newUserWithDeposit();
        Deposit before = depositRepository.findByUserId(userId).orElseThrow();
        PaymentCreationView view = paymentCreationOrchestrator.createPayment(userId, TOP_UP_AMOUNT, null);
        Long paymentId = view.payment().getId();

        Payment fresh = paymentRepository.findById(paymentId).orElseThrow();
        fresh.shortenExpiryTo(OffsetDateTime.now().minusMinutes(1));
        paymentRepository.save(fresh);

        boolean expired = paymentExpiryService.expireOnePayment(paymentId);
        assertThat(expired).isTrue();

        Payment reloaded = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.EXPIRED);

        assertThat(paymentEventRepository.findByPaymentId(paymentId))
                .extracting(e -> e.getType(), e -> e.getSource())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(PaymentEventType.CREATED,
                                com.example.autoauction.payment.domain.PaymentEventSource.CLIENT),
                        org.assertj.core.groups.Tuple.tuple(PaymentEventType.INITIATION_ATTEMPTED,
                                com.example.autoauction.payment.domain.PaymentEventSource.CLIENT),
                        org.assertj.core.groups.Tuple.tuple(PaymentEventType.EXPIRED,
                                com.example.autoauction.payment.domain.PaymentEventSource.SCHEDULER));

        Deposit after = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(after.getBalance()).as("expiry never credits a deposit").isEqualByComparingTo(before.getBalance());
        assertThat(depositRepository.findTransactionsByUserId(userId).stream()
                .filter(tx -> tx.getType() == DepositTransaction.TransactionType.PAYMENT_CREDIT))
                .as("no PAYMENT_CREDIT ledger row was created by expiry")
                .isEmpty();
    }

    // ======================================================================
    // Attack 7 — idempotency semantics are unchanged: a repeated creation with the same key
    // returns the SAME (still CREATED) payment, never a second one, never a provider re-call.
    // ======================================================================

    @Test
    @DisplayName("Attack 7: repeated creation with the same idempotencyKey replays the same CREATED payment, " +
            "and Phase 38's INITIATION_ATTEMPTED checkpoint is recorded exactly once - never on the replay")
    void attack7_idempotentReplay_unchanged() {
        Long userId = newUserWithDeposit();
        String key = "idem-fail-closed-" + System.nanoTime();

        PaymentCreationView first = paymentCreationOrchestrator.createPayment(userId, TOP_UP_AMOUNT, key);
        PaymentCreationView second = paymentCreationOrchestrator.createPayment(userId, TOP_UP_AMOUNT, key);

        assertThat(second.payment().getId()).isEqualTo(first.payment().getId());
        assertThat(second.payment().getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(paymentRepository.findByCreationIdempotencyKey(key)).hasValueSatisfying(
                p -> assertThat(p.getId()).isEqualTo(first.payment().getId()));

        // Phase 38 scenario G: the replay must never invoke recordInitiationAttempted (or the
        // provider) again - exactly one checkpoint exists for the one genuinely-new creation.
        assertThat(paymentEventRepository.findByPaymentId(first.payment().getId()))
                .extracting(e -> e.getType())
                .containsExactly(PaymentEventType.CREATED, PaymentEventType.INITIATION_ATTEMPTED);
    }
}
