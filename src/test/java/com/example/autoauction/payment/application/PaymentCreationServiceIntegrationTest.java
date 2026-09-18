package com.example.autoauction.payment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentEventSource;
import com.example.autoauction.payment.domain.PaymentEventType;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.payment.infrastructure.provider.FakePaymentProviderPort;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1.5 §26: sequential application-level coverage of {@link PaymentCreationService}/
 * {@link PaymentCreationOrchestrator} - mirrors {@code PaymentConfirmationServiceIntegrationTest}'s
 * own structure/conventions (P1.4).
 */
@DisplayName("P1.5: payment creation - application behaviour")
class PaymentCreationServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PaymentCreationService paymentCreationService;
    @Autowired private PaymentCreationOrchestrator paymentCreationOrchestrator;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentEventRepository paymentEventRepository;
    @Autowired private FakePaymentProviderPort fakeProvider;
    @Autowired private DepositRepository depositRepository;
    @Autowired private DepositService depositService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    @BeforeEach
    void resetProviderDefaultStub() {
        // The fake provider bean is a singleton shared across the whole Postgres test context -
        // a default stub left over from another test (or another test class, if the context is
        // cached and reused) must never leak and silently change this test's expected outcome.
        fakeProvider.clearDefaultStub();
    }

    private Long newUserWithDeposit() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "pcs" + System.nanoTime(), "pcs" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, BigDecimal.ZERO);
        return userId;
    }

    @Test
    @DisplayName("1. valid creation: new Payment, CREATED, correct amount/currency/userId")
    void validCreation() {
        Long userId = newUserWithDeposit();
        PaymentCreationOutcome outcome = paymentCreationService.createPayment(userId, new BigDecimal("50000"), null);
        assertThat(outcome.isNewCreation()).isTrue();
        Payment p = outcome.payment();
        assertThat(p.getId()).isNotNull();
        assertThat(p.getUserId()).isEqualTo(userId);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(p.getAmountRequested()).isEqualByComparingTo("50000");
        assertThat(p.getCurrency()).isEqualTo("KGS");
    }

    @Test
    @DisplayName("2. amount below minimum (100 KGS) rejected")
    void amountBelowMinimum_rejected() {
        Long userId = newUserWithDeposit();
        assertThatThrownBy(() -> paymentCreationService.createPayment(userId, new BigDecimal("50"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("3. amount above maximum (200,000 KGS) rejected")
    void amountAboveMaximum_rejected() {
        Long userId = newUserWithDeposit();
        assertThatThrownBy(() -> paymentCreationService.createPayment(userId, new BigDecimal("200001"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("4. valid boundary amounts (exactly 100 and exactly 200,000) accepted")
    void boundaryAmounts_accepted() {
        Long userId = newUserWithDeposit();
        assertThat(paymentCreationService.createPayment(userId, new BigDecimal("100"), null).isNewCreation()).isTrue();
        assertThat(paymentCreationService.createPayment(userId, new BigDecimal("200000"), null).isNewCreation()).isTrue();
    }

    @Test
    @DisplayName("7. null idempotency key: two requests create two distinct Payments")
    void nullIdempotencyKey_createsDistinctPayments() {
        Long userId = newUserWithDeposit();
        Payment a = paymentCreationService.createPayment(userId, new BigDecimal("1000"), null).payment();
        Payment b = paymentCreationService.createPayment(userId, new BigDecimal("1000"), null).payment();
        assertThat(a.getId()).isNotEqualTo(b.getId());
        assertThat(a.getMerchantReference()).isNotEqualTo(b.getMerchantReference());
    }

    @Test
    @DisplayName("8. same key + same user + same amount: returns the existing Payment, no new row")
    void sameKeySameRequest_returnsExisting() {
        Long userId = newUserWithDeposit();
        String key = "idem-" + System.nanoTime();
        PaymentCreationOutcome first = paymentCreationService.createPayment(userId, new BigDecimal("1000"), key);
        assertThat(first.isNewCreation()).isTrue();

        PaymentCreationOutcome replay = paymentCreationService.createPayment(userId, new BigDecimal("1000"), key);
        assertThat(replay.isNewCreation()).isFalse();
        assertThat(replay.payment().getId()).isEqualTo(first.payment().getId());
    }

    @Test
    @DisplayName("9. same key + same user + different amount: conflict, original untouched")
    void sameKeyDifferentAmount_conflicts() {
        Long userId = newUserWithDeposit();
        String key = "idem-" + System.nanoTime();
        Payment original = paymentCreationService.createPayment(userId, new BigDecimal("1000"), key).payment();

        assertThatThrownBy(() -> paymentCreationService.createPayment(userId, new BigDecimal("2000"), key))
                .isInstanceOf(IllegalStateException.class);

        Payment stillOriginal = paymentRepository.findById(original.getId()).orElseThrow();
        assertThat(stillOriginal.getAmountRequested()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("10. same key + different user: ALWAYS conflict, never the other user's Payment")
    void sameKeyDifferentUser_alwaysConflicts() {
        Long userA = newUserWithDeposit();
        Long userB = newUserWithDeposit();
        String key = "idem-cross-user-" + System.nanoTime();
        paymentCreationService.createPayment(userA, new BigDecimal("1000"), key);

        assertThatThrownBy(() -> paymentCreationService.createPayment(userB, new BigDecimal("1000"), key))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("11. blank/whitespace idempotency key normalizes to null - behaves exactly like no key")
    void blankIdempotencyKey_behavesAsNull() {
        Long userId = newUserWithDeposit();
        Payment a = paymentCreationService.createPayment(userId, new BigDecimal("1000"), "").payment();
        Payment b = paymentCreationService.createPayment(userId, new BigDecimal("1000"), "   ").payment();
        assertThat(a.getId()).isNotEqualTo(b.getId());
        assertThat(a.getCreationIdempotencyKey()).isNull();
        assertThat(b.getCreationIdempotencyKey()).isNull();
    }

    @Test
    @DisplayName("idempotency key longer than 100 characters is rejected")
    void overlongIdempotencyKey_rejected() {
        Long userId = newUserWithDeposit();
        String tooLong = "k".repeat(101);
        assertThatThrownBy(() -> paymentCreationService.createPayment(userId, new BigDecimal("1000"), tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("resolveConflictAfterRace: null key -> null (caller must rethrow the original persistence exception)")
    void resolveConflictAfterRace_nullKey_returnsNull() {
        Long userId = newUserWithDeposit();
        assertThat(paymentCreationService.resolveConflictAfterRace(userId, new BigDecimal("1000"), null)).isNull();
    }

    @Test
    @DisplayName("resolveConflictAfterRace: key exists, same owner/fingerprint -> replay outcome")
    void resolveConflictAfterRace_matchingFingerprint_returnsReplay() {
        Long userId = newUserWithDeposit();
        String key = "idem-resolve-" + System.nanoTime();
        Payment existing = paymentCreationService.createPayment(userId, new BigDecimal("1500"), key).payment();

        PaymentCreationOutcome resolved = paymentCreationService.resolveConflictAfterRace(userId, new BigDecimal("1500"), key);
        assertThat(resolved).isNotNull();
        assertThat(resolved.isNewCreation()).isFalse();
        assertThat(resolved.payment().getId()).isEqualTo(existing.getId());
    }

    @Test
    @DisplayName("resolveConflictAfterRace: key exists, different owner -> conflict, never the other user's Payment")
    void resolveConflictAfterRace_differentOwner_throwsConflict() {
        Long userA = newUserWithDeposit();
        Long userB = newUserWithDeposit();
        String key = "idem-resolve-cross-" + System.nanoTime();
        paymentCreationService.createPayment(userA, new BigDecimal("1500"), key);

        assertThatThrownBy(() -> paymentCreationService.resolveConflictAfterRace(userB, new BigDecimal("1500"), key))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("12. merchantReference is server-generated, opaque, unique")
    void merchantReference_serverGenerated() {
        Long userId = newUserWithDeposit();
        Payment a = paymentCreationService.createPayment(userId, new BigDecimal("1000"), null).payment();
        Payment b = paymentCreationService.createPayment(userId, new BigDecimal("1000"), null).payment();
        assertThat(a.getMerchantReference()).isNotBlank().startsWith("PAY-");
        assertThat(a.getMerchantReference()).isNotEqualTo(b.getMerchantReference());
    }

    @Test
    @DisplayName("13. CREATED event is persisted atomically with the Payment insert")
    void createdEvent_exists() {
        Long userId = newUserWithDeposit();
        Payment p = paymentCreationService.createPayment(userId, new BigDecimal("1000"), null).payment();
        var events = paymentEventRepository.findByPaymentId(p.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getType()).isEqualTo(PaymentEventType.CREATED);
        assertThat(events.get(0).getSource()).isEqualTo(PaymentEventSource.CLIENT);
    }

    @Test
    @DisplayName("14. orchestrator end-to-end: provider accepted -> PENDING, providerReference set, "
            + "PaymentEvent(INITIATION_ACCEPTED) durably recorded before PaymentEvent(PENDING)")
    void providerAccepted_endToEnd_transitionsToPending() {
        Long userId = newUserWithDeposit();
        String providerRef = "PSP-REF-" + System.nanoTime();
        fakeProvider.stubDefaultAccepted(providerRef);

        PaymentCreationView view = paymentCreationOrchestrator.createPayment(userId, new BigDecimal("1000"), null);
        assertThat(view.payment().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(view.payment().getProviderReference()).isNotBlank();

        var events = paymentEventRepository.findByPaymentId(view.payment().getId());
        assertThat(events).extracting(e -> e.getType())
                .containsExactly(PaymentEventType.CREATED, PaymentEventType.INITIATION_ACCEPTED, PaymentEventType.PENDING);
        assertThat(events.get(1).getRawPayload()).isEqualTo("providerReference=" + providerRef);
    }

    @Test
    @DisplayName("15. orchestrator end-to-end: provider explicitly rejected -> FAILED, no credit, PaymentEvent(FAILED) appended")
    void providerRejected_endToEnd_transitionsToFailed() {
        Long userId = newUserWithDeposit();
        fakeProvider.stubDefaultRejected();

        PaymentCreationView view = paymentCreationOrchestrator.createPayment(userId, new BigDecimal("1000"), null);
        assertThat(view.payment().getStatus()).isEqualTo(PaymentStatus.FAILED);

        var events = paymentEventRepository.findByPaymentId(view.payment().getId());
        assertThat(events).extracting(e -> e.getType())
                .containsExactly(PaymentEventType.CREATED, PaymentEventType.FAILED);
    }

    @Test
    @DisplayName("16. orchestrator end-to-end: provider ambiguous (no stub) -> Payment remains CREATED, only the CREATED event exists")
    void providerAmbiguous_endToEnd_remainsCreated() {
        Long userId = newUserWithDeposit();
        fakeProvider.clearDefaultStub(); // explicit: no stub at all -> ambiguous

        PaymentCreationView view = paymentCreationOrchestrator.createPayment(userId, new BigDecimal("1000"), null);
        assertThat(view.payment().getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(view.payment().getProviderReference()).isNull();
        assertThat(view.checkoutUrl()).isNull();
        assertThat(view.qrPayload()).isNull();

        Payment reloaded = paymentRepository.findById(view.payment().getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.CREATED);
        // Only the CREATED event - ambiguity never appends anything (never FAILED, never a
        // fabricated CALLBACK_RECEIVED/POLL_RESULT for something creation has no business
        // observing).
        var events = paymentEventRepository.findByPaymentId(reloaded.getId());
        assertThat(events).extracting(e -> e.getType()).containsExactly(PaymentEventType.CREATED);
    }

    @Test
    @DisplayName("17. payment creation never credits Deposit, in CREATED, PENDING, or FAILED")
    void creationNeverCreditsDeposit() {
        Long userId = newUserWithDeposit();
        Payment created = paymentCreationService.createPayment(userId, new BigDecimal("5000"), null).payment();
        paymentCreationService.finalizeAccepted(created.getId(), "PSP-REF-NOCREDIT");

        long creditCount = depositRepository.findTransactionsByUserId(userId).stream()
                .filter(tx -> tx.getType() == DepositTransaction.TransactionType.PAYMENT_CREDIT)
                .count();
        assertThat(creditCount).isZero();
        assertThat(depositRepository.findByUserId(userId).orElseThrow().getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("18. expiresAt = createdAt + 30 minutes exactly")
    void expiresAt_isCreatedAtPlus30Minutes() {
        Long userId = newUserWithDeposit();
        Payment p = paymentCreationService.createPayment(userId, new BigDecimal("1000"), null).payment();
        assertThat(Duration.between(p.getCreatedAt(), p.getExpiresAt())).isEqualTo(Duration.ofMinutes(30));
    }
}
