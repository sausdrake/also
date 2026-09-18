package com.example.autoauction.payment.infrastructure.scheduler;

import com.example.autoauction.payment.application.PaymentCreationOrchestrator;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 33: trigger only, mirroring {@link PaymentExpiryScheduler}'s own shape - no recovery
 * business logic lives here. Periodically calls the existing, unchanged {@code finalizeAccepted}
 * authority (via {@link PaymentCreationOrchestrator#recoverFromInitiationEvidence}) for each
 * {@code CREATED} payment that has durable {@code INITIATION_ACCEPTED} evidence - i.e. a
 * provider-initiation response the application successfully received but never finished
 * persisting as {@code PENDING} (a process crash or transaction failure in that specific window).
 *
 * <p>Deliberately does NOT call {@code PaymentProviderPort} at all (neither {@code
 * initiatePayment} nor {@code getPaymentStatus}) - it only ever completes an already-accepted,
 * already-durably-known initiation. Runs at a deliberately conservative interval (unlike the
 * time-sensitive {@link PaymentExpiryScheduler}/{@code PaymentConfirmationScheduler}, both at
 * 60s): recovering a rare crash-window event has no urgency comparable to expiry/confirmation
 * polling, matching {@code MediaReconciliationScheduler}'s own precedent for a similarly
 * low-urgency, maintenance-style periodic job.
 *
 * <p>Does NOT solve a provider response that was never received at all (a timeout) - there is no
 * evidence to recover from in that case; this scheduler simply finds no candidate for it.
 */
@Slf4j
@Component
public class PaymentInitiationRecoveryScheduler {

    private final PaymentRepository paymentRepository;
    private final PaymentCreationOrchestrator paymentCreationOrchestrator;

    public PaymentInitiationRecoveryScheduler(PaymentRepository paymentRepository,
                                               PaymentCreationOrchestrator paymentCreationOrchestrator) {
        this.paymentRepository = paymentRepository;
        this.paymentCreationOrchestrator = paymentCreationOrchestrator;
        log.info("PaymentInitiationRecoveryScheduler initialized");
    }

    @Scheduled(fixedDelay = 300_000)
    public void recoverInterruptedInitiations() {
        List<Long> candidates;
        try {
            candidates = paymentRepository.findCreatedWithInitiationEvidenceCandidateIds();
        } catch (Exception e) {
            log.error("Payment initiation recovery candidate scan failed: {}", e.getMessage(), e);
            return;
        }
        int recovered = 0;
        for (Long paymentId : candidates) {
            try {
                Payment result = paymentCreationOrchestrator.recoverFromInitiationEvidence(paymentId);
                if (result.getStatus() == PaymentStatus.PENDING) {
                    recovered++;
                }
            } catch (Exception e) {
                log.error("Payment initiation recovery failed for payment {}: {}", paymentId, e.getMessage(), e);
            }
        }
        if (recovered > 0) {
            log.info("Payment initiation recovery: {} of {} candidate(s) recovered", recovered, candidates.size());
        }
    }
}
