package com.example.autoauction.payment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentEvent;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * P1.4 §K: real-PostgreSQL proof that a failure occurring AFTER the provider has already
 * authoritatively reported SUCCEEDED - but before the local transaction commits - rolls back
 * every write the canonical operation made (Payment transition, ledger credit, PaymentEvent)
 * atomically, and that a subsequent retry of the exact same canonical operation completes
 * normally with no duplicate financial movement.
 *
 * <p>Mirrors this project's own proven {@code DepositCreditPrimitivePgRollbackIntegrationTest}
 * fault-injection convention exactly: a Mockito {@code @SpyBean} wraps the REAL,
 * Testcontainers-backed {@link PaymentEventRepository} bean, and only its {@code append(...)}
 * call is made to throw - the preceding {@code markSucceeded}/{@code save} and
 * {@code creditFromPayment} calls run for real, against the real database, so this proves actual
 * transactional rollback, not merely that an exception was thrown somewhere. This injection
 * mechanism exists only inside this test class; no production code is changed or weakened, and no
 * self-invocation is used to force the failure.
 */
@DisplayName("P1.4 §K: confirmPayment rollback-after-provider-success, then successful retry")
class PaymentConfirmationRollbackPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PaymentConfirmationOrchestrator orchestrator;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private FakePaymentProviderPort fakeProvider;

    @SpyBean
    private PaymentEventRepository paymentEventRepository;

    private Long newUserWithDeposit() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "pcr" + System.nanoTime(), "pcr" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, BigDecimal.ZERO);
        return userId;
    }

    private long paymentCreditCount(Long userId) {
        return depositRepository.findTransactionsByUserId(userId).stream()
                .filter(tx -> tx.getType() == DepositTransaction.TransactionType.PAYMENT_CREDIT)
                .count();
    }

    @Test
    @DisplayName("provider SUCCEEDED + local transaction failure on the final write -> full rollback; retry succeeds exactly once")
    void rollbackAfterProviderSuccess_ThenRetrySucceedsExactlyOnce() {
        Long userId = newUserWithDeposit();
        String ref = "PAY-RB-" + System.nanoTime();
        String providerRef = "PSP-RB-" + System.nanoTime();
        String providerTxnId = "TXN-RB-" + System.nanoTime();
        OffsetDateTime now = OffsetDateTime.now();
        Payment p = new Payment(userId, ref, new BigDecimal("500.00"), null, now, now.plusMinutes(30));
        p.markPending(providerRef);
        p = paymentRepository.save(p);
        fakeProvider.stubStatus(providerRef, new PaymentProviderPort.ProviderPaymentStatus(
                PaymentProviderPort.ProviderPaymentState.SUCCEEDED, new BigDecimal("500.00"), "KGS", providerTxnId));

        doThrow(new RuntimeException("P1.4 test-only injected failure - not production behavior"))
                .when(paymentEventRepository).append(any(PaymentEvent.class));

        assertThatThrownBy(() -> orchestrator.confirmPayment(ref))
                .hasMessageContaining("injected failure");

        Payment afterFailedAttempt = paymentRepository.findById(p.getId()).orElseThrow();
        assertThat(afterFailedAttempt.getStatus())
                .as("Payment transition must have rolled back")
                .isEqualTo(PaymentStatus.PENDING);
        assertThat(afterFailedAttempt.getProviderTransactionId()).isNull();
        assertThat(paymentCreditCount(userId)).as("deposit credit must have rolled back too").isZero();
        assertThat(depositRepository.findByUserId(userId).orElseThrow().getBalance())
                .as("balance unchanged")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // Stop injecting the failure, then retry the IDENTICAL canonical operation. The provider
        // still (truthfully) reports SUCCEEDED - nothing about the authoritative fact changed.
        org.mockito.Mockito.reset(paymentEventRepository);

        Payment afterRetry = orchestrator.confirmPayment(ref);

        assertThat(afterRetry.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(paymentCreditCount(userId)).as("exactly one PAYMENT_CREDIT after the successful retry").isEqualTo(1);
        assertThat(depositRepository.findByUserId(userId).orElseThrow().getBalance())
                .isEqualByComparingTo("500.00");
    }
}
