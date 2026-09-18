package com.example.autoauction.payment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentEvent;
import com.example.autoauction.payment.domain.PaymentEventType;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
import com.example.autoauction.payment.domain.port.PaymentProviderPort;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * P1.5 §28: real-PostgreSQL proof of genuine transactional rollback at both TX boundaries of
 * payment creation - mirrors {@code PaymentConfirmationRollbackPgIntegrationTest}'s (P1.4)
 * {@code @SpyBean}-wraps-a-real-bean fault-injection convention exactly: only
 * {@code PaymentEventRepository.append(...)} is made to throw: the preceding real writes
 * (Deposit lock read, Payment insert/update) run for real against the real database, so this
 * proves actual rollback, not merely that an exception was thrown somewhere.
 */
@DisplayName("P1.5 §28: payment creation rollback-on-failure, then successful retry")
class PaymentCreationRollbackPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PaymentCreationService paymentCreationService;
    @Autowired private PaymentCreationOrchestrator paymentCreationOrchestrator;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private DepositService depositService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    @SpyBean
    private PaymentEventRepository paymentEventRepository;

    @SpyBean
    private PaymentProviderPort paymentProviderPort;

    private Long newUserWithDeposit() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "pcrb" + System.nanoTime(), "pcrb" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, BigDecimal.ZERO);
        return userId;
    }

    @Test
    @DisplayName("§28 creation TX rollback: CREATED-event persistence failure -> neither the Payment nor the event remains")
    void creationTransactionRollback_onEventFailure_noPaymentNoEventRemain() {
        Long userId = newUserWithDeposit();
        String key = "idem-rb-" + System.nanoTime();

        doThrow(new RuntimeException("P1.5 test-only injected failure - not production behavior"))
                .when(paymentEventRepository).append(any(PaymentEvent.class));

        assertThatThrownBy(() -> paymentCreationService.createPayment(userId, new BigDecimal("1000"), key))
                .hasMessageContaining("injected failure");

        assertThat(paymentRepository.findByCreationIdempotencyKey(key))
                .as("the whole creation transaction (Payment insert + event) must have rolled back atomically")
                .isEmpty();

        org.mockito.Mockito.reset(paymentEventRepository);

        Payment retried = paymentCreationService.createPayment(userId, new BigDecimal("1000"), key).payment();
        assertThat(retried.getStatus()).isEqualTo(PaymentStatus.CREATED);
    }

    @Test
    @DisplayName("§28 provider-transition (TX2) rollback: finalizeAccepted failure -> Payment remains CREATED, no partial state; retry succeeds")
    void providerTransitionRollback_onFinalizeFailure_paymentRemainsCreated() {
        Long userId = newUserWithDeposit();
        Payment created = paymentCreationService.createPayment(userId, new BigDecimal("1000"), null).payment();

        doThrow(new RuntimeException("P1.5 test-only injected failure - not production behavior"))
                .when(paymentEventRepository).append(any(PaymentEvent.class));

        assertThatThrownBy(() -> paymentCreationService.finalizeAccepted(created.getId(), "PSP-REF-RB"))
                .hasMessageContaining("injected failure");

        Payment afterFailedFinalize = paymentRepository.findById(created.getId()).orElseThrow();
        assertThat(afterFailedFinalize.getStatus())
                .as("TX2 must have rolled back entirely - no partial providerReference/status change")
                .isEqualTo(PaymentStatus.CREATED);
        assertThat(afterFailedFinalize.getProviderReference()).isNull();

        org.mockito.Mockito.reset(paymentEventRepository);

        Payment afterRetry = paymentCreationService.finalizeAccepted(created.getId(), "PSP-REF-RB");
        assertThat(afterRetry.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(afterRetry.getProviderReference()).isEqualTo("PSP-REF-RB");
    }

    @Test
    @DisplayName("Phase 38 scenario B: INITIATION_ATTEMPTED checkpoint-transaction failure -> CREATED+CREATED-event "
            + "survive, no INITIATION_ATTEMPTED event exists, and the provider is never invoked")
    void checkpointTransactionRollback_onInitiationAttemptedFailure_providerNeverCalled() {
        Long userId = newUserWithDeposit();
        String key = "idem-ckpt-rb-" + System.nanoTime();

        // Scoped narrowly to the INITIATION_ATTEMPTED append only - the preceding CREATED event
        // append (inside paymentCreationService.createPayment, a separate, already-committed
        // transaction) must succeed for real, exactly as §28's own convention requires.
        doThrow(new RuntimeException("P1.5 test-only injected checkpoint failure - not production behavior"))
                .when(paymentEventRepository)
                .append(argThat(e -> e != null && e.getType() == PaymentEventType.INITIATION_ATTEMPTED));

        assertThatThrownBy(() -> paymentCreationOrchestrator.createPayment(userId, new BigDecimal("1000"), key))
                .hasMessageContaining("injected checkpoint failure");

        Payment reloaded = paymentRepository.findByCreationIdempotencyKey(key).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("TX1 (CREATED + CREATED-event) already committed before the checkpoint call - it must survive")
                .isEqualTo(PaymentStatus.CREATED);
        assertThat(paymentEventRepository.findByPaymentId(reloaded.getId()))
                .as("the failed checkpoint transaction must have rolled back entirely - no INITIATION_ATTEMPTED event")
                .extracting(e -> e.getType())
                .containsExactly(PaymentEventType.CREATED);

        verifyNoInteractions(paymentProviderPort);

        org.mockito.Mockito.reset(paymentEventRepository);

        PaymentCreationView retried = paymentCreationOrchestrator.createPayment(userId, new BigDecimal("1000"), key);
        assertThat(retried.payment().getId()).isEqualTo(reloaded.getId());
    }
}
