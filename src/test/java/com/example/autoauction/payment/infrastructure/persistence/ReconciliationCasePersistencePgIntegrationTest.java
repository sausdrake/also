package com.example.autoauction.payment.infrastructure.persistence;

import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.ReconciliationCase;
import com.example.autoauction.payment.domain.ReconciliationCaseType;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import com.example.autoauction.payment.domain.port.ReconciliationCaseRepository;
import com.example.autoauction.support.AbstractPostgresIntegrationTest;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 15: real-PostgreSQL proof that {@link ReconciliationCaseRepositoryAdapter} correctly
 * round-trips {@link ReconciliationCase} through {@link JpaReconciliationCaseEntity} against the
 * actual V42 schema, and that the DB-level {@code UNIQUE(payment_id, type)} constraint - the
 * concurrency boundary {@code PaymentConfirmationService.openMismatchCaseIfAbsent}'s check-then-act
 * pattern relies on the caller's pessimistic payment-row lock to never actually hit - is real, not
 * merely documented. H2 is not used here because this is exactly the kind of schema-level
 * constraint (and enum-to-CHECK-constraint agreement) this project's own convention (see
 * {@link com.example.autoauction.identity.infrastructure.persistence.IdentityPersistencePgIntegrationTest})
 * always verifies against a real PostgreSQL.
 */
@DisplayName("Reconciliation persistence - real-PostgreSQL round-trip and schema invariants")
class ReconciliationCasePersistencePgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private ReconciliationCaseRepository reconciliationCaseRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-10T10:00:00Z");

    private Long newPayment() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "recpg" + System.nanoTime(), "recpg" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();

        Payment payment = new Payment(userId, "merchant-ref-" + System.nanoTime(), new BigDecimal("500.00"),
                null, T0, T0.plusMinutes(30));
        return paymentRepository.save(payment).getId();
    }

    @Test
    @DisplayName("A/B: a new OPEN case round-trips through the adapter with all fields intact")
    void openCase_SaveAndLoad_AllFieldsSurvive() {
        Long paymentId = newPayment();
        ReconciliationCase saved = reconciliationCaseRepository.save(
                new ReconciliationCase(paymentId, ReconciliationCaseType.MISMATCH, T0));

        assertThat(saved.getId()).isNotNull();

        ReconciliationCase loaded = reconciliationCaseRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(saved.getId());
        assertThat(loaded.getPaymentId()).isEqualTo(paymentId);
        assertThat(loaded.getType()).isEqualTo(ReconciliationCaseType.MISMATCH);
        assertThat(loaded.getStatus().name()).isEqualTo("OPEN");
        assertThat(loaded.isOpen()).isTrue();
        assertThat(loaded.getOpenedAt()).isEqualTo(T0);
        assertThat(loaded.getResolvedByAdminId()).isNull();
        assertThat(loaded.getResolutionNote()).isNull();
        assertThat(loaded.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("C: a RESOLVED case (via the real domain invariant, not bypassed) round-trips its resolution fields")
    void resolvedCase_SaveAndLoad_ResolutionFieldsSurvive() {
        Long paymentId = newPayment();
        ReconciliationCase c = reconciliationCaseRepository.save(
                new ReconciliationCase(paymentId, ReconciliationCaseType.DUPLICATE, T0));

        // Real domain behavior, not a hand-built "resolved" row - resolve() is the only legal way
        // to reach RESOLVED, exactly as production code would use it.
        OffsetDateTime resolvedAt = T0.plusHours(2);
        c.resolve(77L, "duplicate webhook confirmed benign, no action needed", resolvedAt);
        reconciliationCaseRepository.save(c);

        ReconciliationCase loaded = reconciliationCaseRepository.findById(c.getId()).orElseThrow();
        assertThat(loaded.getStatus().name()).isEqualTo("RESOLVED");
        assertThat(loaded.isOpen()).isFalse();
        assertThat(loaded.getResolvedByAdminId()).isEqualTo(77L);
        assertThat(loaded.getResolutionNote()).isEqualTo("duplicate webhook confirmed benign, no action needed");
        assertThat(loaded.getResolvedAt()).isEqualTo(resolvedAt);
    }

    @Test
    @DisplayName("D: findByPaymentIdAndType returns the exact match and nothing for a different payment/type")
    void findByPaymentIdAndType_ExactMatchOnly() {
        Long paymentA = newPayment();
        Long paymentB = newPayment();
        reconciliationCaseRepository.save(new ReconciliationCase(paymentA, ReconciliationCaseType.MISMATCH, T0));

        Optional<ReconciliationCase> hit =
                reconciliationCaseRepository.findByPaymentIdAndType(paymentA, ReconciliationCaseType.MISMATCH);
        assertThat(hit).isPresent();
        assertThat(hit.get().getPaymentId()).isEqualTo(paymentA);

        assertThat(reconciliationCaseRepository.findByPaymentIdAndType(paymentA, ReconciliationCaseType.DUPLICATE))
                .as("same payment, different type must not match")
                .isEmpty();
        assertThat(reconciliationCaseRepository.findByPaymentIdAndType(paymentB, ReconciliationCaseType.MISMATCH))
                .as("different payment, same type must not match")
                .isEmpty();
    }

    @Test
    @DisplayName("E: UNIQUE(payment_id, type) rejects a second case of the same type for the same payment")
    void uniqueConstraint_RejectsDuplicatePaymentAndType() {
        Long paymentId = newPayment();
        reconciliationCaseRepository.save(new ReconciliationCase(paymentId, ReconciliationCaseType.MISMATCH, T0));

        // Deliberately calling save() directly a second time for the same (payment_id, type),
        // bypassing the findByPaymentIdAndType presence-check that PaymentConfirmationService
        // always performs first - this isolates the DATABASE constraint as the final authority,
        // independent of any application-level guard. The IDENTITY generation strategy on
        // JpaReconciliationCaseEntity forces an immediate INSERT on persist(), so the violation
        // surfaces synchronously here rather than being deferred to a later flush.
        assertThatThrownBy(() -> reconciliationCaseRepository.save(
                new ReconciliationCase(paymentId, ReconciliationCaseType.MISMATCH, T0.plusMinutes(1))))
                .isInstanceOf(DataIntegrityViolationException.class);

        // A different type for the SAME payment remains perfectly legal (the constraint is
        // per-type, not per-payment) - confirms the constraint's exact intended scope, not a
        // broader "one case per payment" rule.
        ReconciliationCase other = reconciliationCaseRepository.save(
                new ReconciliationCase(paymentId, ReconciliationCaseType.STUCK, T0));
        assertThat(other.getId()).isNotNull();
    }

    @Test
    @DisplayName("Phase 17: optimistic locking - two stale concurrent resolutions, only the first wins")
    void optimisticLocking_ConcurrentResolution_SecondStaleWriterRejected() {
        Long paymentId = newPayment();
        ReconciliationCase saved = reconciliationCaseRepository.save(
                new ReconciliationCase(paymentId, ReconciliationCaseType.MISMATCH, T0));
        assertThat(saved.getVersion()).as("a freshly-persisted case starts at version 0").isEqualTo(0L);

        // Two independent loads of the SAME row - simulating two admins opening the case at the
        // same time, each getting their own in-memory copy at version 0.
        ReconciliationCase loadedByAdminA = reconciliationCaseRepository.findById(saved.getId()).orElseThrow();
        ReconciliationCase loadedByAdminB = reconciliationCaseRepository.findById(saved.getId()).orElseThrow();

        loadedByAdminA.resolve(1L, "admin A resolved first", T0.plusMinutes(1));
        ReconciliationCase afterA = reconciliationCaseRepository.save(loadedByAdminA);
        assertThat(afterA.getVersion()).as("version increments on the winning update").isEqualTo(1L);

        loadedByAdminB.resolve(2L, "admin B resolved second, using stale data", T0.plusMinutes(2));
        assertThatThrownBy(() -> reconciliationCaseRepository.save(loadedByAdminB))
                .as("the second writer's version (0) no longer matches the row's current version (1) - no silent last-write-wins")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // Admin A's resolution remains intact and unmodified by the rejected second attempt.
        ReconciliationCase finalState = reconciliationCaseRepository.findById(saved.getId()).orElseThrow();
        assertThat(finalState.getStatus().name()).isEqualTo("RESOLVED");
        assertThat(finalState.getResolvedByAdminId()).isEqualTo(1L);
        assertThat(finalState.getResolutionNote()).isEqualTo("admin A resolved first");
    }

    @Test
    @DisplayName("Phase 17: normal, non-concurrent save/load still works with the version column present")
    void normalSaveAndLoad_StillWorksWithVersionColumn() {
        Long paymentId = newPayment();
        ReconciliationCase saved = reconciliationCaseRepository.save(
                new ReconciliationCase(paymentId, ReconciliationCaseType.MISMATCH, T0));

        saved.resolve(5L, "no conflict, sequential resolve", T0.plusMinutes(1));
        ReconciliationCase resolved = reconciliationCaseRepository.save(saved);

        ReconciliationCase loaded = reconciliationCaseRepository.findById(resolved.getId()).orElseThrow();
        assertThat(loaded.getStatus().name()).isEqualTo("RESOLVED");
        assertThat(loaded.getVersion()).isEqualTo(1L);
    }
}
