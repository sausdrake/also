package com.example.autoauction.payment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentEventType;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
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
import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

/**
 * Phase 41 / T10: real-PostgreSQL proof that the evidence-backed expiry (CREATED -> PENDING ->
 * EXPIRED) is one atomic transaction. Mirrors {@code PaymentCreationRollbackPgIntegrationTest}'s
 * {@code @SpyBean}-wraps-a-real-bean convention: only the {@code EXPIRED} event append is made to
 * throw, so the earlier real writes in the same transaction (the {@code PENDING} transition, its
 * event) genuinely run against the real database first - proving rollback, not merely that an
 * exception was thrown somewhere.
 */
@DisplayName("Phase 41: evidence-backed expiry rolls back atomically")
class PaymentExpiryEvidenceRollbackPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PaymentExpiryService paymentExpiryService;
    @Autowired private PaymentCreationService paymentCreationService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private DepositService depositService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    @SpyBean
    private PaymentEventRepository paymentEventRepository;

    private Long newUserWithDeposit() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "pxrb" + System.nanoTime(), "pxrb" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, BigDecimal.ZERO);
        return userId;
    }

    @Test
    @DisplayName("T10: failure appending EXPIRED rolls back the PENDING transition too - payment stays CREATED, evidence intact; the next sweep succeeds")
    void expiredAppendFailure_rollsBackEverything_thenRetrySucceeds() {
        Long userId = newUserWithDeposit();
        String ref = "PSP-P41-T10-" + System.nanoTime();
        Payment p = paymentCreationService.createPayment(userId, new BigDecimal("500.00"), null).payment();
        paymentCreationService.recordInitiationAccepted(p.getId(), ref);
        Payment fresh = paymentRepository.findById(p.getId()).orElseThrow();
        fresh.shortenExpiryTo(OffsetDateTime.now().minusMinutes(1));
        paymentRepository.save(fresh);

        doThrow(new RuntimeException("Phase 41 test-only injected failure - not production behavior"))
                .when(paymentEventRepository)
                .append(argThat(e -> e != null && e.getType() == PaymentEventType.EXPIRED));

        assertThatThrownBy(() -> paymentExpiryService.expireOnePayment(p.getId()))
                .hasMessageContaining("injected failure");

        Payment afterFailure = paymentRepository.findById(p.getId()).orElseThrow();
        assertThat(afterFailure.getStatus()).as("the intermediate PENDING must not have committed")
                .isEqualTo(PaymentStatus.CREATED);
        assertThat(afterFailure.getProviderReference()).isNull();
        assertThat(paymentEventRepository.findByPaymentId(p.getId()))
                .extracting(e -> e.getType())
                .as("no PENDING and no EXPIRED event survive; the acceptance evidence is untouched")
                .containsExactly(PaymentEventType.CREATED, PaymentEventType.INITIATION_ACCEPTED);

        org.mockito.Mockito.reset(paymentEventRepository);

        assertThat(paymentExpiryService.expireOnePayment(p.getId())).isTrue();

        Payment afterRetry = paymentRepository.findById(p.getId()).orElseThrow();
        assertThat(afterRetry.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(afterRetry.getProviderReference()).isEqualTo(ref);
        assertThat(paymentEventRepository.findByPaymentId(p.getId()))
                .extracting(e -> e.getType())
                .containsExactly(PaymentEventType.CREATED, PaymentEventType.INITIATION_ACCEPTED,
                        PaymentEventType.PENDING, PaymentEventType.EXPIRED);
    }
}
