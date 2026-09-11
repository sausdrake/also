package com.example.autoauction.payment.infrastructure.persistence;

import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentEvent;
import com.example.autoauction.payment.domain.PaymentEventSource;
import com.example.autoauction.payment.domain.PaymentEventType;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.ReconciliationCase;
import com.example.autoauction.payment.domain.ReconciliationCaseStatus;
import com.example.autoauction.payment.domain.ReconciliationCaseType;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 2 · Step 1: proves the V42 schema starts against real PostgreSQL (Flyway V1..V42), the
 * three new aggregates round-trip through their mappers with enum values intact, and the money
 * identity constraints are actually present in the database. This is the foundation smoke test -
 * the full money-path suite (atomic credit, duplicate semantics, concurrency, reconciliation)
 * comes after Steps 2-5.
 */
@DisplayName("Slice 2 · Step 1: payment persistence smoke (PostgreSQL / Flyway V1..V42)")
class PaymentPersistenceSmokePgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentEventRepository paymentEventRepository;
    @Autowired private ReconciliationCaseRepository reconciliationCaseRepository;
    @Autowired private SpringDataPaymentJpaRepository springDataPaymentJpaRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private Long newUserId() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        long n = System.nanoTime();
        User u = new User(null, "pay" + n, "pay" + n + "@test.com", "encoded", true, Set.of(role));
        return userRepository.save(u).getId();
    }

    private Payment newIntent(Long userId, String ref) {
        OffsetDateTime now = OffsetDateTime.now();
        return new Payment(userId, ref, new BigDecimal("10000.00"), null, now, now.plusMinutes(30));
    }

    @Test
    @DisplayName("payment round-trips: CREATED -> PENDING -> SUCCEEDED with enum + write-once fields intact")
    void paymentRoundTrip() {
        Long userId = newUserId();
        String ref = "PAY-" + System.nanoTime();

        Payment saved = paymentRepository.save(newIntent(userId, ref));
        assertThat(saved.getId()).isNotNull();

        Payment reloaded = paymentRepository.findByMerchantReference(ref).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(reloaded.getCurrency()).isEqualTo("KGS");
        assertThat(reloaded.getAmountRequested()).isEqualByComparingTo("10000.00");
        assertThat(reloaded.getAmountConfirmed()).isNull();
        assertThat(reloaded.getProviderTransactionId()).isNull();
        assertThat(reloaded.getProviderReference()).isNull(); // providerReference: null round-trip

        String providerReference = "PSP-REF-" + System.nanoTime();
        reloaded.markPending(providerReference);
        paymentRepository.save(reloaded);

        Payment pending = paymentRepository.findById(saved.getId()).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(pending.getProviderReference())
                .as("providerReference must round-trip through the database exactly")
                .isEqualTo(providerReference);

        pending.markSucceeded("TX-" + System.nanoTime(), new BigDecimal("10000.00"));
        paymentRepository.save(pending);

        Payment done = paymentRepository.findById(saved.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(done.getAmountConfirmed()).isEqualByComparingTo("10000.00");
        assertThat(done.getProviderTransactionId()).startsWith("TX-");
        // providerReference survives the PENDING -> SUCCEEDED transition unchanged, and remains
        // independent from (not required to equal) providerTransactionId.
        assertThat(done.getProviderReference()).isEqualTo(providerReference);
        assertThat(done.getProviderReference()).isNotEqualTo(done.getProviderTransactionId());

        assertThat(paymentRepository.findByProviderTransactionId(done.getProviderTransactionId())).isPresent();
        assertThat(paymentRepository.sumSucceededConfirmedByUserSince(userId, OffsetDateTime.now().minusHours(1)))
                .isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("payment_event is append-only and round-trips with enum type + source")
    void paymentEventRoundTrip() {
        Long userId = newUserId();
        Payment p = paymentRepository.save(newIntent(userId, "PAY-" + System.nanoTime()));

        paymentEventRepository.append(new PaymentEvent(p.getId(), PaymentEventType.CREATED,
                PaymentEventSource.CLIENT, null, OffsetDateTime.now()));
        paymentEventRepository.append(new PaymentEvent(p.getId(), PaymentEventType.CALLBACK_RECEIVED,
                PaymentEventSource.PROVIDER_WEBHOOK, "{\"raw\":\"payload\"}", OffsetDateTime.now()));

        List<PaymentEvent> events = paymentEventRepository.findByPaymentId(p.getId());
        assertThat(events).hasSize(2);
        assertThat(events).extracting(PaymentEvent::getType)
                .containsExactly(PaymentEventType.CREATED, PaymentEventType.CALLBACK_RECEIVED);
        assertThat(events.get(1).getSource()).isEqualTo(PaymentEventSource.PROVIDER_WEBHOOK);
        assertThat(events.get(1).getRawPayload()).contains("payload");
    }

    @Test
    @DisplayName("reconciliation_case: OPEN -> RESOLVED round-trip; findOpen / findByPaymentIdAndType")
    void reconciliationCaseRoundTrip() {
        Long userId = newUserId();
        Payment p = paymentRepository.save(newIntent(userId, "PAY-" + System.nanoTime()));

        ReconciliationCase opened = reconciliationCaseRepository.save(
                new ReconciliationCase(p.getId(), ReconciliationCaseType.MISMATCH, OffsetDateTime.now()));
        assertThat(opened.getStatus()).isEqualTo(ReconciliationCaseStatus.OPEN);

        assertThat(reconciliationCaseRepository.findByPaymentIdAndType(p.getId(), ReconciliationCaseType.MISMATCH)).isPresent();
        assertThat(reconciliationCaseRepository.findOpen()).anyMatch(c -> c.getId().equals(opened.getId()));

        ReconciliationCase toResolve = reconciliationCaseRepository.findById(opened.getId()).orElseThrow();
        toResolve.resolve(999L, "handled", OffsetDateTime.now());
        reconciliationCaseRepository.save(toResolve);

        ReconciliationCase resolved = reconciliationCaseRepository.findById(opened.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(ReconciliationCaseStatus.RESOLVED);
        assertThat(resolved.getResolvedByAdminId()).isEqualTo(999L);
        assertThat(reconciliationCaseRepository.findOpen()).noneMatch(c -> c.getId().equals(opened.getId()));
    }

    @Test
    @DisplayName("uk_payment_merchant_reference is enforced by the database")
    void merchantReferenceUnique() {
        Long userId = newUserId();
        String ref = "PAY-DUP-" + System.nanoTime();
        paymentRepository.save(newIntent(userId, ref));
        assertThatThrownBy(() -> paymentRepository.save(newIntent(userId, ref)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("uk_reconciliation_case_payment_type is enforced by the database")
    void reconciliationCasePaymentTypeUnique() {
        Long userId = newUserId();
        Payment p = paymentRepository.save(newIntent(userId, "PAY-" + System.nanoTime()));
        reconciliationCaseRepository.save(new ReconciliationCase(p.getId(), ReconciliationCaseType.DUPLICATE, OffsetDateTime.now()));
        assertThatThrownBy(() -> reconciliationCaseRepository.save(
                new ReconciliationCase(p.getId(), ReconciliationCaseType.DUPLICATE, OffsetDateTime.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("findByCreationIdempotencyKey: returns the matching payment; Optional.empty() for an unknown key")
    void creationIdempotencyKeyLookup() {
        Long userId = newUserId();
        String key = "creation-key-" + System.nanoTime();
        OffsetDateTime now = OffsetDateTime.now();
        Payment withKey = paymentRepository.save(
                new Payment(userId, "PAY-" + System.nanoTime(), new BigDecimal("10000.00"), key, now, now.plusMinutes(30)));

        Payment found = paymentRepository.findByCreationIdempotencyKey(key).orElseThrow();
        assertThat(found.getId()).isEqualTo(withKey.getId());
        assertThat(found.getCreationIdempotencyKey()).isEqualTo(key);

        assertThat(paymentRepository.findByCreationIdempotencyKey("no-such-key-" + System.nanoTime())).isEmpty();
    }

    @Test
    @DisplayName("payment_currency_kgs_check rejects a non-KGS row at the database")
    void currencyCheckConstraint() {
        Long userId = newUserId();
        OffsetDateTime now = OffsetDateTime.now();
        JpaPaymentEntity bad = new JpaPaymentEntity(null, userId, "PAY-USD-" + System.nanoTime(),
                new BigDecimal("10000.00"), null, "USD", PaymentStatus.CREATED, null, null, null, false,
                now, now.plusMinutes(30), now);
        assertThatThrownBy(() -> springDataPaymentJpaRepository.saveAndFlush(bad))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
