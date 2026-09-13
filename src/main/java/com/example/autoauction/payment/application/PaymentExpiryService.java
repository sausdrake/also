package com.example.autoauction.payment.application;

import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentEvent;
import com.example.autoauction.payment.domain.PaymentEventSource;
import com.example.autoauction.payment.domain.PaymentEventType;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * P1.5: bounds the lifetime of a payment's capacity reservation. Owns BOTH pieces of expiry
 * logic - the candidate lookup ({@link #findExpiredCandidateIds}) and the single-payment
 * transactional transition ({@link #expireOnePayment}) - so {@link PaymentExpiryScheduler} (a
 * separate bean) can call each without ever invoking a {@code @Transactional} method on itself.
 *
 * <p>Deliberately ONE small transaction per candidate, not one transaction over the whole sweep:
 * a single long-running transaction holding many {@code Payment} row locks in sequence would
 * needlessly extend lock duration and blast radius for no benefit - each candidate is entirely
 * independent.
 *
 * <p>The candidate read is NOT authoritative (see
 * {@link PaymentRepository#findExpiredCandidateIds}) - {@link #expireOnePayment} always re-reads
 * the specific row WITH the pessimistic lock and rechecks both its status and its
 * {@code expiresAt} before mutating anything, so a payment already confirmed/expired by a
 * concurrent operation (confirmation, or a previous sweep run) is always left untouched.
 */
@Slf4j
@Service
public class PaymentExpiryService {

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final Clock clock;

    public PaymentExpiryService(PaymentRepository paymentRepository,
                                 PaymentEventRepository paymentEventRepository,
                                 Clock clock) {
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.clock = clock;
    }

    /** Non-transactional candidate scan - deliberately not authoritative; see class Javadoc. */
    public List<Long> findExpiredCandidateIds() {
        return paymentRepository.findExpiredCandidateIds(OffsetDateTime.now(clock));
    }

    /**
     * One payment, one transaction. Returns {@code true} only if this call actually performed
     * the {@code -> EXPIRED} transition; {@code false} for every safe no-op (not found, already
     * terminal, or no longer actually expired by the time the lock was acquired).
     */
    @Transactional
    public boolean expireOnePayment(Long paymentId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
        if (payment == null) {
            return false;
        }
        if (payment.getStatus() != PaymentStatus.CREATED && payment.getStatus() != PaymentStatus.PENDING) {
            // Already terminal (SUCCEEDED/FAILED/CANCELLED/EXPIRED) - possibly by a confirmation
            // that won the race for this exact row. Never touch SUCCEEDED/FAILED/CANCELLED;
            // never double-append an EXPIRED event for an already-EXPIRED row.
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!payment.getExpiresAt().isBefore(now)) {
            // Re-checked under lock: not actually expired as of right now (e.g. expiresAt was
            // concurrently shortened/extended-within-limits, or the candidate read was stale by
            // the time this lock was acquired). Conservative no-op, never forced.
            return false;
        }
        payment.markExpired();
        paymentRepository.save(payment);
        paymentEventRepository.append(new PaymentEvent(
                payment.getId(), PaymentEventType.EXPIRED, PaymentEventSource.SCHEDULER, null, now));
        log.info("expireOnePayment: payment {} expired (expiresAt={})", payment.getId(), payment.getExpiresAt());
        return true;
    }
}
