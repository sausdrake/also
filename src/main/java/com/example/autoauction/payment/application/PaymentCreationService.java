package com.example.autoauction.payment.application;

import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentAmountPolicy;
import com.example.autoauction.payment.domain.PaymentEvent;
import com.example.autoauction.payment.domain.PaymentEventSource;
import com.example.autoauction.payment.domain.PaymentEventType;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * P1.5: the transactional application service behind payment creation. Owns BOTH transaction
 * boundaries of the locked two-transaction creation sequence:
 * <ul>
 *   <li>{@link #createPayment} - TX1: lock the user's Deposit row, check idempotency, calculate
 *       combined daily-limit capacity, insert {@code Payment(CREATED)} + its {@code CREATED}
 *       event, all-or-nothing.</li>
 *   <li>{@link #finalizeAccepted} / {@link #finalizeRejected} - TX2: transition the already-
 *       committed {@code CREATED} row to {@code PENDING}/{@code FAILED} after the provider call
 *       returns. Deliberately separate from TX1 (never the same transaction) so the Deposit lock
 *       is never held across the outbound provider call - see
 *       {@link PaymentCreationOrchestrator} for the full reasoning.</li>
 * </ul>
 *
 * <p><b>Must be called only via a genuine cross-bean reference</b> from
 * {@link PaymentCreationOrchestrator}, never via self-invocation - the same already-proven
 * requirement as {@code PaymentConfirmationService}/{@code PaymentConfirmationOrchestrator}
 * (P1.4), reapplied here rather than re-litigated.
 */
@Slf4j
@Service
public class PaymentCreationService {

    /** Locked (P1.2/P1.3): the platform's own local TTL for a newly-created Payment. */
    static final Duration PAYMENT_TTL = Duration.ofMinutes(30);

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final DepositRepository depositRepository;
    private final Clock clock;

    public PaymentCreationService(PaymentRepository paymentRepository,
                                   PaymentEventRepository paymentEventRepository,
                                   DepositRepository depositRepository,
                                   Clock clock) {
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.depositRepository = depositRepository;
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /**
     * TX1. {@code amount} validity is checked before the lock (a pure, context-free check - no
     * concurrent state could change whether it is in range, so re-checking it under lock would be
     * pointless). Idempotency and daily-limit capacity, in contrast, both depend on concurrent
     * database state and are deliberately evaluated ONLY after the Deposit row lock is held.
     */
    @Transactional
    public PaymentCreationOutcome createPayment(Long userId, BigDecimal amountRequested, String rawIdempotencyKey) {
        PaymentAmountPolicy.validateAmount(amountRequested);
        String idempotencyKey = normalizeIdempotencyKey(rawIdempotencyKey);

        depositRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("Депозит не найден для пользователя: " + userId));

        if (idempotencyKey != null) {
            var existing = paymentRepository.findByCreationIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return resolveAgainstExisting(existing.get(), userId, amountRequested, idempotencyKey);
            }
        }

        OffsetDateTime now = now();
        checkCapacity(userId, amountRequested, now);

        Payment payment = new Payment(userId, generateMerchantReference(), amountRequested,
                idempotencyKey, now, now.plus(PAYMENT_TTL));

        // §8: deliberately NOT a try/catch around the flush in THIS method. Hibernate forbids
        // any further use of a session after a failed flush ("don't flush the Session after an
        // exception occurs") - a unique-constraint violation here poisons this transaction, so
        // the only safe recovery is to let it propagate and roll back cleanly, then resolve the
        // race in a genuinely FRESH transaction (see resolveConflictAfterRace). Proven necessary
        // empirically: an earlier version of this method caught the exception and tried to
        // re-read in the same transaction, which threw org.hibernate.AssertionFailure instead of
        // ever reaching the intended conflict/replay logic.
        Payment saved = paymentRepository.createAndFlush(payment);
        paymentEventRepository.append(new PaymentEvent(
                saved.getId(), PaymentEventType.CREATED, PaymentEventSource.CLIENT, null, now));
        return new PaymentCreationOutcome(saved, true);
    }

    /**
     * §8 race recovery - called by {@link PaymentCreationOrchestrator} ONLY after
     * {@link #createPayment} throws {@link DataIntegrityViolationException}, in a genuinely
     * fresh transaction/session. Re-applies the exact same fingerprint/ownership logic as the
     * pre-existing-key path in {@link #createPayment}. If no matching row is found (e.g. the
     * race was actually an unrelated, astronomically-unlikely merchant_reference collision), the
     * original exception must be rethrown by the caller rather than silently masked - this method
     * signals that by returning {@code null}.
     */
    @Transactional
    public PaymentCreationOutcome resolveConflictAfterRace(Long userId, BigDecimal amountRequested, String rawIdempotencyKey) {
        String idempotencyKey = normalizeIdempotencyKey(rawIdempotencyKey);
        if (idempotencyKey == null) {
            return null;
        }
        return paymentRepository.findByCreationIdempotencyKey(idempotencyKey)
                .map(winner -> resolveAgainstExisting(winner, userId, amountRequested, idempotencyKey))
                .orElse(null);
    }

    /**
     * TX2 (accept path). A second invocation on an already-non-CREATED row (should not happen in
     * practice - the orchestrator calls this exactly once per new creation) is a safe no-op,
     * mirroring {@code PaymentConfirmationService}'s own already-terminal handling.
     */
    @Transactional
    public Payment finalizeAccepted(Long paymentId, String providerReference) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Платёж не найден: " + paymentId));
        if (payment.getStatus() != PaymentStatus.CREATED) {
            log.info("finalizeAccepted: payment {} no longer CREATED ({}), no-op", payment.getId(), payment.getStatus());
            return payment;
        }
        payment.markPending(providerReference);
        paymentRepository.save(payment);
        paymentEventRepository.append(new PaymentEvent(
                payment.getId(), PaymentEventType.PENDING, PaymentEventSource.CLIENT, null, now()));
        return payment;
    }

    /** TX2 (explicit synchronous rejection path). */
    @Transactional
    public Payment finalizeRejected(Long paymentId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Платёж не найден: " + paymentId));
        if (payment.getStatus() != PaymentStatus.CREATED) {
            log.info("finalizeRejected: payment {} no longer CREATED ({}), no-op", payment.getId(), payment.getStatus());
            return payment;
        }
        payment.markFailed();
        paymentRepository.save(payment);
        paymentEventRepository.append(new PaymentEvent(
                payment.getId(), PaymentEventType.FAILED, PaymentEventSource.CLIENT, null, now()));
        return payment;
    }

    private PaymentCreationOutcome resolveAgainstExisting(Payment existing, Long userId, BigDecimal amountRequested,
                                                            String idempotencyKey) {
        // Security invariant (§7/§29): a key collision across different users is ALWAYS a
        // conflict, never a replay - regardless of whether the amount happens to match.
        if (!existing.getUserId().equals(userId)) {
            throw new IllegalStateException(
                    "Ключ идемпотентности '" + idempotencyKey + "' уже используется другим пользователем");
        }
        boolean sameFingerprint = existing.getAmountRequested().compareTo(amountRequested) == 0
                && Payment.CURRENCY_KGS.equals(existing.getCurrency());
        if (!sameFingerprint) {
            throw new IllegalStateException(
                    "Ключ идемпотентности '" + idempotencyKey + "' уже использован для другого запроса "
                            + "(другая сумма или валюта)");
        }
        return new PaymentCreationOutcome(existing, false);
    }

    private void checkCapacity(Long userId, BigDecimal amountRequested, OffsetDateTime now) {
        BigDecimal usage = paymentRepository.calculateCapacityUsage(userId, now.minusHours(24));
        if (usage.add(amountRequested).compareTo(PaymentAmountPolicy.DAILY_LIMIT) > 0) {
            throw new IllegalStateException(
                    "Превышен суточный лимит пополнения (" + PaymentAmountPolicy.DAILY_LIMIT.toPlainString() + " KGS за 24 часа)");
        }
    }

    private static String normalizeIdempotencyKey(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Ключ идемпотентности не может быть длиннее " + MAX_IDEMPOTENCY_KEY_LENGTH + " символов");
        }
        return trimmed;
    }

    private static String generateMerchantReference() {
        return "PAY-" + UUID.randomUUID();
    }
}
