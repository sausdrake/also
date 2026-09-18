package com.example.autoauction.payment.infrastructure.persistence;

import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.port.PaymentRepository;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 29: proves {@link PaymentRepository#findConfirmationCandidateReferences()}'s exact
 * predicate against real PostgreSQL - {@code status IN (PENDING, EXPIRED) AND providerReference
 * IS NOT NULL} - so the new {@code PaymentConfirmationScheduler} never receives a candidate the
 * canonical confirmation operation cannot safely act on (a {@code CREATED} payment with no
 * provider handle, or an {@code EXPIRED} payment that reached that status directly from {@code
 * CREATED} without ever having one).
 */
@DisplayName("Phase 29: confirmation-polling candidate query")
class PaymentConfirmationCandidateQueryPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private Long newUser() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "pcq" + System.nanoTime(), "pcq" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        return userRepository.save(u).getId();
    }

    private Payment basePayment(Long userId, String merchantReference) {
        OffsetDateTime now = OffsetDateTime.now();
        return new Payment(userId, merchantReference, new BigDecimal("500.00"), null, now, now.plusMinutes(30));
    }

    @Test
    @DisplayName("A. PENDING + providerReference -> selected")
    void pendingWithProviderReference_Selected() {
        Long userId = newUser();
        String ref = "PAY-CANDA-" + System.nanoTime();
        Payment p = basePayment(userId, ref);
        p.markPending("PSP-A-" + System.nanoTime());
        paymentRepository.save(p);

        assertThat(paymentRepository.findConfirmationCandidateReferences()).contains(ref);
    }

    @Test
    @DisplayName("B. EXPIRED + providerReference -> selected")
    void expiredWithProviderReference_Selected() {
        Long userId = newUser();
        String ref = "PAY-CANDB-" + System.nanoTime();
        Payment p = basePayment(userId, ref);
        p.markPending("PSP-B-" + System.nanoTime());
        p.markExpired();
        paymentRepository.save(p);

        assertThat(paymentRepository.findConfirmationCandidateReferences()).contains(ref);
    }

    @Test
    @DisplayName("C. CREATED + providerReference null -> excluded")
    void createdWithoutProviderReference_Excluded() {
        Long userId = newUser();
        String ref = "PAY-CANDC-" + System.nanoTime();
        Payment p = basePayment(userId, ref);
        paymentRepository.save(p);

        assertThat(paymentRepository.findConfirmationCandidateReferences()).doesNotContain(ref);
    }

    @Test
    @DisplayName("E. EXPIRED + providerReference null (direct CREATED->EXPIRED sweep) -> excluded")
    void expiredWithoutProviderReference_Excluded() {
        Long userId = newUser();
        String ref = "PAY-CANDE-" + System.nanoTime();
        Payment p = basePayment(userId, ref);
        p.markExpired();
        assertThat(p.getProviderReference()).isNull();
        paymentRepository.save(p);

        assertThat(paymentRepository.findConfirmationCandidateReferences()).doesNotContain(ref);
    }

    @Test
    @DisplayName("F. SUCCEEDED -> excluded")
    void succeeded_Excluded() {
        Long userId = newUser();
        String ref = "PAY-CANDF-" + System.nanoTime();
        Payment p = basePayment(userId, ref);
        p.markPending("PSP-F-" + System.nanoTime());
        p.markSucceeded("TXN-F-" + System.nanoTime(), new BigDecimal("500.00"));
        paymentRepository.save(p);

        assertThat(paymentRepository.findConfirmationCandidateReferences()).doesNotContain(ref);
    }

    @Test
    @DisplayName("G. FAILED -> excluded")
    void failed_Excluded() {
        Long userId = newUser();
        String ref = "PAY-CANDG-" + System.nanoTime();
        Payment p = basePayment(userId, ref);
        p.markFailed();
        paymentRepository.save(p);

        assertThat(paymentRepository.findConfirmationCandidateReferences()).doesNotContain(ref);
    }

    @Test
    @DisplayName("H. CANCELLED -> excluded")
    void cancelled_Excluded() {
        Long userId = newUser();
        String ref = "PAY-CANDH-" + System.nanoTime();
        Payment p = basePayment(userId, ref);
        p.markCancelled();
        paymentRepository.save(p);

        assertThat(paymentRepository.findConfirmationCandidateReferences()).doesNotContain(ref);
    }

    @Test
    @DisplayName("PaymentStatus has no CANCELLED-with-providerReference-then-EXPIRED path; D covered structurally: " +
            "CREATED never carries a providerReference (write-once, assigned only by markPending)")
    void createdCanNeverCarryProviderReference_ByConstruction() {
        Long userId = newUser();
        Payment p = basePayment(userId, "PAY-CANDD-" + System.nanoTime());
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(p.getProviderReference()).isNull();
    }
}
