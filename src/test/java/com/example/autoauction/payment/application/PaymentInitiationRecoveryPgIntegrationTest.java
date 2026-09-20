package com.example.autoauction.payment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.domain.DepositTransaction;
import com.example.autoauction.deposit.domain.port.DepositRepository;
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
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 33: real-PostgreSQL proof of the provider-initiation recovery slice - {@link
 * PaymentCreationService#recordInitiationAccepted} (the durable checkpoint) and {@link
 * PaymentCreationOrchestrator#recoverFromInitiationEvidence} (the recovery operation), including
 * the crash-window reconstruction (evidence recorded, finalizeAccepted never called - simulated
 * by calling the two independent, separately-callable production methods in that exact partial
 * order, exactly reproducing what a process crash between them leaves behind) and the
 * original-finalize-vs-recovery race, mirroring this project's own proven
 * {@code PaymentConfirmationConcurrencyPgIntegrationTest} two-thread/CyclicBarrier convention.
 */
@DisplayName("Phase 33: provider-initiation recovery")
class PaymentInitiationRecoveryPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private PaymentCreationService paymentCreationService;
    @Autowired private PaymentCreationOrchestrator paymentCreationOrchestrator;
    @Autowired private PaymentExpiryService paymentExpiryService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentEventRepository paymentEventRepository;
    @Autowired private DepositService depositService;
    @Autowired private DepositRepository depositRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private Long newUserWithDeposit() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User u = new User(null, "pir" + System.nanoTime(), "pir" + System.nanoTime() + "@test.com",
                "encoded", true, Set.of(role));
        Long userId = userRepository.save(u).getId();
        depositService.createDeposit(userId, BigDecimal.ZERO);
        return userId;
    }

    /** A CREATED payment with durable evidence but no finalizeAccepted call - the exact residue a
     * real process crash between the two independent, existing production calls leaves behind. */
    private Payment newCreatedWithEvidence(Long userId, String providerReference) {
        Payment p = paymentCreationService.createPayment(userId, new BigDecimal("500.00"), null).payment();
        paymentCreationService.recordInitiationAccepted(p.getId(), providerReference);
        return p;
    }

    private long paymentCreditCount(Long userId) {
        return depositRepository.findTransactionsByUserId(userId).stream()
                .filter(tx -> tx.getType() == DepositTransaction.TransactionType.PAYMENT_CREDIT)
                .count();
    }

    private void runConcurrently(Runnable a, Runnable b) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        executor.submit(() -> { try { barrier.await(5, TimeUnit.SECONDS); a.run(); } catch (Exception e) { throw new RuntimeException(e); } });
        executor.submit(() -> { try { barrier.await(5, TimeUnit.SECONDS); b.run(); } catch (Exception e) { throw new RuntimeException(e); } });
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }

    // ==================== Checkpoint write ====================

    @Test
    @DisplayName("recordInitiationAccepted rejects a blank providerReference - fail closed, no event written")
    void recordInitiationAccepted_BlankReference_RejectedNoEvent() {
        Long userId = newUserWithDeposit();
        Payment p = paymentCreationService.createPayment(userId, new BigDecimal("500.00"), null).payment();

        assertThatThrownBy(() -> paymentCreationService.recordInitiationAccepted(p.getId(), "  "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(paymentEventRepository.findByPaymentId(p.getId()))
                .extracting(e -> e.getType())
                .containsExactly(PaymentEventType.CREATED);
    }

    @Test
    @DisplayName("recordInitiationAccepted persists exactly one INITIATION_ACCEPTED event with the exact reference in the payload")
    void recordInitiationAccepted_PersistsEvidence() {
        Long userId = newUserWithDeposit();
        String ref = "PSP-CKPT-" + System.nanoTime();
        Payment p = newCreatedWithEvidence(userId, ref);

        List<com.example.autoauction.payment.domain.PaymentEvent> evidence =
                paymentEventRepository.findByPaymentIdAndType(p.getId(), PaymentEventType.INITIATION_ACCEPTED);
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).getRawPayload()).isEqualTo("providerReference=" + ref);
        assertThat(paymentRepository.findById(p.getId()).orElseThrow().getStatus())
                .as("checkpoint alone must never transition Payment")
                .isEqualTo(PaymentStatus.CREATED);
    }

    // ==================== Recovery: the crash window itself ====================

    @Test
    @DisplayName("recovery completes the interrupted CREATED -> PENDING transition using durable evidence, no provider call")
    void recovery_CompletesInterruptedTransition() {
        Long userId = newUserWithDeposit();
        String ref = "PSP-REC-" + System.nanoTime();
        Payment p = newCreatedWithEvidence(userId, ref);

        Payment result = paymentCreationOrchestrator.recoverFromInitiationEvidence(p.getId());

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(result.getProviderReference()).isEqualTo(ref);
        assertThat(paymentEventRepository.findByPaymentId(p.getId()))
                .extracting(e -> e.getType())
                .containsExactly(PaymentEventType.CREATED, PaymentEventType.INITIATION_ACCEPTED, PaymentEventType.PENDING);
        assertThat(paymentCreditCount(userId)).as("recovery must never credit").isZero();
    }

    @Test
    @DisplayName("candidate query selects a CREATED payment with evidence and excludes one without")
    void candidateQuery_SelectsOnlyCreatedWithEvidence() {
        Long userId = newUserWithDeposit();
        Payment withEvidence = newCreatedWithEvidence(userId, "PSP-CAND-A-" + System.nanoTime());
        Payment withoutEvidence = paymentCreationService.createPayment(userId, new BigDecimal("500.00"), null).payment();

        List<Long> candidates = paymentRepository.findCreatedWithInitiationEvidenceCandidateIds();

        assertThat(candidates).contains(withEvidence.getId());
        assertThat(candidates).doesNotContain(withoutEvidence.getId());
    }

    @Test
    @DisplayName("Phase 38 scenario F: a CREATED payment with only INITIATION_ATTEMPTED (no INITIATION_ACCEPTED) "
            + "is excluded from the recovery candidate query and cannot be recovered")
    void candidateQuery_ExcludesInitiationAttemptedAlone() {
        Long userId = newUserWithDeposit();
        Payment attemptedOnly = paymentCreationService.createPayment(userId, new BigDecimal("500.00"), null).payment();
        paymentCreationService.recordInitiationAttempted(attemptedOnly.getId());

        assertThat(paymentEventRepository.findByPaymentId(attemptedOnly.getId()))
                .extracting(e -> e.getType())
                .containsExactly(PaymentEventType.CREATED, PaymentEventType.INITIATION_ATTEMPTED);

        List<Long> candidates = paymentRepository.findCreatedWithInitiationEvidenceCandidateIds();
        assertThat(candidates)
                .as("INITIATION_ATTEMPTED must never be mistaken for recovery-authoritative INITIATION_ACCEPTED evidence")
                .doesNotContain(attemptedOnly.getId());

        assertThatThrownBy(() -> paymentCreationOrchestrator.recoverFromInitiationEvidence(attemptedOnly.getId()))
                .as("the checkpoint alone must never let recovery move this payment to PENDING")
                .isInstanceOf(IllegalStateException.class);

        assertThat(paymentRepository.findById(attemptedOnly.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CREATED);
        assertThat(paymentCreditCount(userId)).isZero();
    }

    @Test
    @DisplayName("recovery with no evidence fails closed - IllegalStateException, no transition")
    void recovery_NoEvidence_FailsClosed() {
        Long userId = newUserWithDeposit();
        Payment p = paymentCreationService.createPayment(userId, new BigDecimal("500.00"), null).payment();

        assertThatThrownBy(() -> paymentCreationOrchestrator.recoverFromInitiationEvidence(p.getId()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(paymentRepository.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CREATED);
    }

    @Test
    @DisplayName("recovery with conflicting evidence fails closed - never guesses a providerReference")
    void recovery_ConflictingEvidence_FailsClosed() {
        Long userId = newUserWithDeposit();
        Payment p = paymentCreationService.createPayment(userId, new BigDecimal("500.00"), null).payment();
        paymentCreationService.recordInitiationAccepted(p.getId(), "PSP-CONFLICT-A-" + System.nanoTime());
        paymentCreationService.recordInitiationAccepted(p.getId(), "PSP-CONFLICT-B-" + System.nanoTime());

        assertThatThrownBy(() -> paymentCreationOrchestrator.recoverFromInitiationEvidence(p.getId()))
                .isInstanceOf(PermanentInitiationEvidenceException.class);

        assertThat(paymentRepository.findById(p.getId()).orElseThrow().getStatus())
                .as("must remain CREATED - no arbitrary choice between conflicting references")
                .isEqualTo(PaymentStatus.CREATED);
        assertThat(paymentCreditCount(userId)).isZero();
    }

    // ==================== Terminal-state / idempotency guards (reused from finalizeAccepted) ====================

    @Test
    @DisplayName("recovery on an already-PENDING payment is a safe no-op (existing finalizeAccepted guard)")
    void recovery_AlreadyPending_NoOp() {
        Long userId = newUserWithDeposit();
        String ref = "PSP-ALREADY-PENDING-" + System.nanoTime();
        Payment p = newCreatedWithEvidence(userId, ref);
        paymentCreationService.finalizeAccepted(p.getId(), ref); // the original request "wins" first

        Payment result = paymentCreationOrchestrator.recoverFromInitiationEvidence(p.getId());

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentEventRepository.findByPaymentId(p.getId()))
                .extracting(e -> e.getType())
                .as("no duplicate PENDING event from the redundant recovery attempt")
                .containsExactly(PaymentEventType.CREATED, PaymentEventType.INITIATION_ACCEPTED, PaymentEventType.PENDING);
    }

    @Test
    @DisplayName("duplicate recovery attempts produce exactly one transition")
    void recovery_CalledTwice_ExactlyOneTransition() {
        Long userId = newUserWithDeposit();
        Payment p = newCreatedWithEvidence(userId, "PSP-DUP-" + System.nanoTime());

        paymentCreationOrchestrator.recoverFromInitiationEvidence(p.getId());
        Payment second = paymentCreationOrchestrator.recoverFromInitiationEvidence(p.getId());

        assertThat(second.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentEventRepository.findByPaymentId(p.getId()))
                .filteredOn(e -> e.getType() == PaymentEventType.PENDING)
                .hasSize(1);
    }

    // ==================== Concurrency: T6 - recovery races the original request ====================

    @RepeatedTest(3)
    @DisplayName("T6: recovery racing the original (slow-but-succeeding) finalizeAccepted call - exactly one transition, no deadlock")
    void recoveryRacesOriginalFinalize_ExactlyOneTransition_NoDeadlock() throws InterruptedException {
        Long userId = newUserWithDeposit();
        String ref = "PSP-RACE-" + System.nanoTime();
        Payment p = newCreatedWithEvidence(userId, ref);

        AtomicReference<Throwable> errA = new AtomicReference<>();
        AtomicReference<Throwable> errB = new AtomicReference<>();

        runConcurrently(
                () -> { try { paymentCreationService.finalizeAccepted(p.getId(), ref); } catch (Throwable t) { errA.set(t); } },
                () -> { try { paymentCreationOrchestrator.recoverFromInitiationEvidence(p.getId()); } catch (Throwable t) { errB.set(t); } });

        assertThat(errA.get()).as("errA=%s", errA.get()).isNull();
        assertThat(errB.get()).as("errB=%s", errB.get()).isNull();

        Payment fresh = paymentRepository.findById(p.getId()).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(fresh.getProviderReference()).isEqualTo(ref);
        assertThat(paymentEventRepository.findByPaymentId(p.getId()))
                .filteredOn(e -> e.getType() == PaymentEventType.PENDING)
                .as("exactly one PENDING transition regardless of which side won the lock")
                .hasSize(1);
    }

    // ==================== Phase 41: recovery races EXPIRY on a CREATED + evidence payment ====================

    @RepeatedTest(3)
    @DisplayName("T8: recovery racing expiry on a CREATED + valid-evidence payment past its TTL: whichever wins, EXPIRED with the reference bound, one PENDING, one EXPIRED, no credit")
    void recoveryRacesExpiry_sameOutcomeWhoeverWins() throws InterruptedException {
        Long userId = newUserWithDeposit();
        String ref = "PSP-P41-T8-" + System.nanoTime();
        Payment p = newCreatedWithEvidence(userId, ref);
        Payment fresh = paymentRepository.findById(p.getId()).orElseThrow();
        fresh.shortenExpiryTo(java.time.OffsetDateTime.now().minusMinutes(1));
        paymentRepository.save(fresh);

        AtomicReference<Throwable> errRecovery = new AtomicReference<>();
        AtomicReference<Throwable> errExpiry = new AtomicReference<>();

        runConcurrently(
                () -> { try { paymentCreationOrchestrator.recoverFromInitiationEvidence(p.getId()); } catch (Throwable t) { errRecovery.set(t); } },
                () -> { try { paymentExpiryService.expireOnePayment(p.getId()); } catch (Throwable t) { errExpiry.set(t); } });

        assertThat(errRecovery.get()).as("errRecovery=%s", errRecovery.get()).isNull();
        assertThat(errExpiry.get()).as("errExpiry=%s", errExpiry.get()).isNull();

        Payment reloaded = paymentRepository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(reloaded.getProviderReference()).isEqualTo(ref);
        assertThat(paymentEventRepository.findByPaymentId(p.getId()))
                .extracting(e -> e.getType())
                .containsExactly(PaymentEventType.CREATED, PaymentEventType.INITIATION_ACCEPTED,
                        PaymentEventType.PENDING, PaymentEventType.EXPIRED);
        assertThat(paymentCreditCount(userId)).isZero();
    }
}
