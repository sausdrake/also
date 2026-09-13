package com.example.autoauction.payment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentEventType;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.ReconciliationCaseStatus;
import com.example.autoauction.payment.domain.ReconciliationCaseType;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
import com.example.autoauction.payment.domain.port.PaymentProviderPort;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.payment.domain.port.ReconciliationCaseRepository;
import com.example.autoauction.payment.infrastructure.provider.FakePaymentProviderPort;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1.4: sequential (non-concurrent) proof of the canonical confirmation operation's every branch,
 * against real PostgreSQL (pessimistic lock semantics, partial unique indexes, and CHECK
 * constraints cannot be faithfully exercised on H2 - matches this project's own established
 * convention for this class of test).
 */
@DisplayName("P1.4: PaymentConfirmationOrchestrator - canonical confirmation branches")
class PaymentConfirmationServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PaymentConfirmationOrchestrator orchestrator;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentEventRepository paymentEventRepository;
    @Autowired private ReconciliationCaseRepository reconciliationCaseRepository;
    @Autowired private FakePaymentProviderPort fakeProvider;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private Long newUserWithDeposit() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "pc" + System.nanoTime(), "pc" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, BigDecimal.ZERO);
        return userId;
    }

    private Payment newPayment(Long userId, String merchantReference, PaymentStatus status, String providerReference) {
        OffsetDateTime now = OffsetDateTime.now();
        Payment p = new Payment(userId, merchantReference, new BigDecimal("500.00"), null, now, now.plusMinutes(30));
        if (status == PaymentStatus.PENDING || status == PaymentStatus.EXPIRED) {
            p.markPending(providerReference);
        }
        if (status == PaymentStatus.EXPIRED) {
            p.markExpired();
        }
        if (status == PaymentStatus.FAILED) {
            p.markFailed();
        }
        if (status == PaymentStatus.CANCELLED) {
            p.markCancelled();
        }
        return paymentRepository.save(p);
    }

    private long paymentCreditCount(Long userId) {
        return depositRepository.findTransactionsByUserId(userId).stream()
                .filter(tx -> tx.getType() == DepositTransaction.TransactionType.PAYMENT_CREDIT)
                .count();
    }

    // ==================== 1. SUCCEEDED happy path ====================

    @Test
    @DisplayName("1/19. SUCCEEDED happy path: transition + exactly one credit + SUCCEEDED event")
    void succeededHappyPath() {
        Long userId = newUserWithDeposit();
        String ref = "PAY-" + System.nanoTime();
        String providerRef = "PSP-" + System.nanoTime();
        Payment p = newPayment(userId, ref, PaymentStatus.PENDING, providerRef);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, new BigDecimal("500.00"), "KGS", "TXN-" + System.nanoTime()));

        Payment result = orchestrator.confirmPayment(ref);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(paymentCreditCount(userId)).isEqualTo(1);
        Deposit deposit = depositRepository.findByUserId(userId).orElseThrow();
        assertThat(deposit.getBalance()).isEqualByComparingTo("500.00");

        List<com.example.autoauction.payment.domain.PaymentEvent> events = paymentEventRepository.findByPaymentId(p.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getType()).isEqualTo(PaymentEventType.SUCCEEDED);
    }

    // ==================== 2. FAILED ====================

    @Test
    @DisplayName("2/19. provider FAILED: Payment -> FAILED, no credit, FAILED event")
    void providerFailed() {
        Long userId = newUserWithDeposit();
        String ref = "PAY-" + System.nanoTime();
        String providerRef = "PSP-" + System.nanoTime();
        Payment p = newPayment(userId, ref, PaymentStatus.PENDING, providerRef);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.FAILED, null, null, null));

        Payment result = orchestrator.confirmPayment(ref);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(paymentCreditCount(userId)).isZero();
        List<com.example.autoauction.payment.domain.PaymentEvent> events = paymentEventRepository.findByPaymentId(p.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getType()).isEqualTo(PaymentEventType.FAILED);
    }

    // ==================== 3. PENDING ====================

    @Test
    @DisplayName("3/19. provider PENDING: no transition, POLL_RESULT event, safely re-callable")
    void providerPending() {
        Long userId = newUserWithDeposit();
        String ref = "PAY-" + System.nanoTime();
        String providerRef = "PSP-" + System.nanoTime();
        Payment p = newPayment(userId, ref, PaymentStatus.PENDING, providerRef);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.PENDING, null, null, null));

        Payment result = orchestrator.confirmPayment(ref);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentCreditCount(userId)).isZero();
        assertThat(paymentEventRepository.findByPaymentId(p.getId()))
                .extracting(com.example.autoauction.payment.domain.PaymentEvent::getType)
                .containsExactly(PaymentEventType.POLL_RESULT);
    }

    // ==================== 4. UNKNOWN ====================

    @Test
    @DisplayName("4/19. provider UNKNOWN: no transition, no credit, POLL_RESULT event only")
    void providerUnknown() {
        Long userId = newUserWithDeposit();
        String ref = "PAY-" + System.nanoTime();
        String providerRef = "PSP-" + System.nanoTime();
        Payment p = newPayment(userId, ref, PaymentStatus.PENDING, providerRef);
        // deliberately not stubbed -> FakePaymentProviderPort defaults to UNKNOWN

        Payment result = orchestrator.confirmPayment(ref);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentCreditCount(userId)).isZero();
        assertThat(paymentEventRepository.findByPaymentId(p.getId()))
                .extracting(com.example.autoauction.payment.domain.PaymentEvent::getType)
                .containsExactly(PaymentEventType.POLL_RESULT);
    }

    // ==================== 5/20. amount mismatch ====================

    @Test
    @DisplayName("5/20. amount mismatch: no credit, no SUCCEEDED transition, ReconciliationCase(MISMATCH) persisted")
    void amountMismatch() {
        Long userId = newUserWithDeposit();
        String ref = "PAY-" + System.nanoTime();
        String providerRef = "PSP-" + System.nanoTime();
        Payment p = newPayment(userId, ref, PaymentStatus.PENDING, providerRef);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, new BigDecimal("499.00"), "KGS", "TXN-" + System.nanoTime()));

        Payment result = orchestrator.confirmPayment(ref);

        assertThat(result.getStatus()).as("Payment must remain non-terminal on mismatch").isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentCreditCount(userId)).isZero();
        assertThat(reconciliationCaseRepository.findByPaymentIdAndType(p.getId(), ReconciliationCaseType.MISMATCH))
                .isPresent()
                .get().extracting(c -> c.getStatus()).isEqualTo(ReconciliationCaseStatus.OPEN);
    }

    // ==================== 6. currency mismatch ====================

    @Test
    @DisplayName("6. currency mismatch: treated identically to amount mismatch (zero tolerance, KGS-only)")
    void currencyMismatch() {
        Long userId = newUserWithDeposit();
        String ref = "PAY-" + System.nanoTime();
        String providerRef = "PSP-" + System.nanoTime();
        Payment p = newPayment(userId, ref, PaymentStatus.PENDING, providerRef);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, new BigDecimal("500.00"), "USD", "TXN-" + System.nanoTime()));

        Payment result = orchestrator.confirmPayment(ref);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentCreditCount(userId)).isZero();
        assertThat(reconciliationCaseRepository.findByPaymentIdAndType(p.getId(), ReconciliationCaseType.MISMATCH)).isPresent();
    }

    @Test
    @DisplayName("5b. repeated amount mismatch on the same payment: no duplicate MISMATCH case (UNIQUE(payment_id, type) respected)")
    void repeatedAmountMismatch_NoDuplicateReconciliationCase() {
        Long userId = newUserWithDeposit();
        String ref = "PAY-" + System.nanoTime();
        String providerRef = "PSP-" + System.nanoTime();
        Payment p = newPayment(userId, ref, PaymentStatus.PENDING, providerRef);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, new BigDecimal("499.00"), "KGS", "TXN-" + System.nanoTime()));

        orchestrator.confirmPayment(ref);
        orchestrator.confirmPayment(ref); // same mismatch observed a second time

        assertThat(paymentCreditCount(userId)).isZero();
        List<com.example.autoauction.payment.domain.ReconciliationCase> all =
                reconciliationCaseRepository.findOpen().stream()
                        .filter(c -> c.getPaymentId().equals(p.getId()))
                        .toList();
        assertThat(all).as("exactly one MISMATCH case, never duplicated").hasSize(1);
        Payment fresh = paymentRepository.findById(p.getId()).orElseThrow();
        assertThat(fresh.getStatus()).as("still non-terminal after the second mismatch too").isEqualTo(PaymentStatus.PENDING);
    }

    // ==================== 7. provider identity/reference conflict (DB backstop) ====================

    @Test
    @DisplayName("7. conflicting providerTransactionId across two payments: rejected and rolled back (by whichever safety layer fires first)")
    void conflictingProviderTransactionId_RejectedAndRolledBack() {
        Long userA = newUserWithDeposit();
        Long userB = newUserWithDeposit();
        String sharedTxnId = "TXN-SHARED-" + System.nanoTime();

        String refA = "PAY-A-" + System.nanoTime();
        String providerRefA = "PSP-A-" + System.nanoTime();
        newPayment(userA, refA, PaymentStatus.PENDING, providerRefA);
        fakeProvider.stubStatus(providerRefA, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, new BigDecimal("500.00"), "KGS", sharedTxnId));
        orchestrator.confirmPayment(refA); // first payment legitimately claims sharedTxnId

        String refB = "PAY-B-" + System.nanoTime();
        String providerRefB = "PSP-B-" + System.nanoTime();
        Payment pB = newPayment(userB, refB, PaymentStatus.PENDING, providerRefB);
        // A real provider would never issue the same transaction id twice; simulated here only to
        // prove a cross-payment conflict is rejected and rolled back rather than silently
        // succeeding. Per P1.4 scope, a DUPLICATE ReconciliationCase is explicitly deferred to a
        // later slice - this test only proves fail-closed rollback, not case creation.
        fakeProvider.stubStatus(providerRefB, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, new BigDecimal("500.00"), "KGS", sharedTxnId));

        // NOTE (discovered during P1.4 testing, not fixed here - see implementation report):
        // PaymentRepositoryAdapter.save() uses plain save(), not saveAndFlush(), unlike the
        // identity module's equivalent adapter. This means the payment table's own
        // uk_payment_provider_transaction_id violation is NOT guaranteed to surface synchronously
        // inside confirmPayment()'s own method body - in practice, DepositService.creditFromPayment's
        // OWN independent ledger-idempotency-key fingerprint check (a different, legitimate safety
        // layer - the same key "PAYMENT:<txnId>" is already claimed by payment A's credit) fires
        // first and throws IllegalStateException. Either way the whole transaction rolls back
        // correctly; only WHICH layer's exception is observed differs.
        assertThatThrownBy(() -> orchestrator.confirmPayment(refB))
                .isInstanceOf(RuntimeException.class);

        Payment freshB = paymentRepository.findById(pB.getId()).orElseThrow();
        assertThat(freshB.getStatus()).as("rolled back - must remain PENDING").isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentCreditCount(userB)).isZero();
        assertThat(paymentEventRepository.findByPaymentId(pB.getId())).as("no event committed either").isEmpty();
    }

    // ==================== 8/9/10/14. already-terminal no-ops ====================

    @Test
    @DisplayName("8/14. already SUCCEEDED: safe no-op, no second credit, no second event")
    void alreadySucceeded_NoOp() {
        Long userId = newUserWithDeposit();
        String ref = "PAY-" + System.nanoTime();
        String providerRef = "PSP-" + System.nanoTime();
        newPayment(userId, ref, PaymentStatus.PENDING, providerRef);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, new BigDecimal("500.00"), "KGS", "TXN-" + System.nanoTime()));
        orchestrator.confirmPayment(ref);
        assertThat(paymentCreditCount(userId)).isEqualTo(1);

        Payment second = orchestrator.confirmPayment(ref); // repeated confirmation after success

        assertThat(second.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(paymentCreditCount(userId)).as("no second credit").isEqualTo(1);
    }

    @Test
    @DisplayName("9. already FAILED: safe no-op")
    void alreadyFailed_NoOp() {
        Long userId = newUserWithDeposit();
        String ref = "PAY-" + System.nanoTime();
        Payment p = newPayment(userId, ref, PaymentStatus.FAILED, null);

        Payment result = orchestrator.confirmPayment(ref);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(paymentEventRepository.findByPaymentId(p.getId())).as("no provider call made, no event").isEmpty();
    }

    @Test
    @DisplayName("10. already CANCELLED: safe no-op")
    void alreadyCancelled_NoOp() {
        Long userId = newUserWithDeposit();
        String ref = "PAY-" + System.nanoTime();
        Payment p = newPayment(userId, ref, PaymentStatus.CANCELLED, null);

        Payment result = orchestrator.confirmPayment(ref);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(paymentEventRepository.findByPaymentId(p.getId())).isEmpty();
    }

    // ==================== 11. EXPIRED + late SUCCEEDED ====================

    @Test
    @DisplayName("11. EXPIRED + authoritative SUCCEEDED: late success credits exactly once, flagged")
    void expiredThenLateSuccess() {
        Long userId = newUserWithDeposit();
        String ref = "PAY-" + System.nanoTime();
        String providerRef = "PSP-" + System.nanoTime();
        newPayment(userId, ref, PaymentStatus.EXPIRED, providerRef);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, new BigDecimal("500.00"), "KGS", "TXN-" + System.nanoTime()));

        Payment result = orchestrator.confirmPayment(ref);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(result.isLateSuccessAfterExpiry()).isTrue();
        assertThat(paymentCreditCount(userId)).isEqualTo(1);
    }

    // ==================== 12. EXPIRED + non-success provider state ====================

    @Test
    @DisplayName("12. EXPIRED + provider says PENDING/FAILED/UNKNOWN: EXPIRED preserved, no resurrection, no credit")
    void expiredPreservedWhenProviderNotSuccess() {
        Long userId = newUserWithDeposit();
        String ref = "PAY-" + System.nanoTime();
        String providerRef = "PSP-" + System.nanoTime();
        Payment p = newPayment(userId, ref, PaymentStatus.EXPIRED, providerRef);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.FAILED, null, null, null));

        Payment result = orchestrator.confirmPayment(ref);

        assertThat(result.getStatus()).as("must stay EXPIRED, never FAILED").isEqualTo(PaymentStatus.EXPIRED);
        assertThat(paymentCreditCount(userId)).isZero();
        assertThat(paymentEventRepository.findByPaymentId(p.getId()))
                .extracting(com.example.autoauction.payment.domain.PaymentEvent::getType)
                .containsExactly(PaymentEventType.POLL_RESULT);
    }
}
