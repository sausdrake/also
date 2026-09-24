package com.example.autoauction.payment.infrastructure.scheduler;

import com.example.autoauction.admin.application.SchedulerHealthSignal;
import com.example.autoauction.payment.application.PaymentConfirmationOrchestrator;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 29: trigger only, mirroring {@link PaymentExpiryScheduler}'s own shape - no confirmation
 * business logic lives here. Before this class existed, {@link
 * PaymentConfirmationOrchestrator#confirmPayment(String)} had zero production callers: a
 * {@code PENDING} payment was never polled, and {@code EXPIRED -&gt; SUCCEEDED} late-success
 * recovery was never triggered. This class closes that gap by periodically calling the existing,
 * unchanged canonical confirmation operation for each eligible payment.
 *
 * <p>Deliberately does NOT call {@code PaymentProviderPort} directly, does NOT call {@code
 * DepositService}, and does NOT mutate {@code Payment} - all of that remains exclusively inside
 * {@code PaymentConfirmationService.confirmPayment}, reached only through the orchestrator. This
 * class has exactly two collaborators: {@link PaymentRepository} (for the non-authoritative
 * candidate read) and {@link PaymentConfirmationOrchestrator} (for the actual, authoritative
 * confirmation attempt) - no new service class was introduced to wrap either.
 *
 * <p>Deliberately ONE call per candidate, sequential, no parallelism/async/executor - the same
 * single-threaded-scheduling-pool convention every other {@code @Scheduled} bean in this
 * application already relies on. A failure confirming one candidate is logged and never aborts
 * the rest of the cycle, exactly like {@link PaymentExpiryScheduler}'s own candidate loop.
 */
@Slf4j
@Component
public class PaymentConfirmationScheduler {

    private final PaymentRepository paymentRepository;
    private final PaymentConfirmationOrchestrator paymentConfirmationOrchestrator;
    private final SchedulerHealthSignal schedulerHealthSignal;

    public PaymentConfirmationScheduler(PaymentRepository paymentRepository,
                                         PaymentConfirmationOrchestrator paymentConfirmationOrchestrator,
                                         SchedulerHealthSignal schedulerHealthSignal) {
        this.paymentRepository = paymentRepository;
        this.paymentConfirmationOrchestrator = paymentConfirmationOrchestrator;
        this.schedulerHealthSignal = schedulerHealthSignal;
        log.info("PaymentConfirmationScheduler initialized");
    }

    @Scheduled(fixedDelay = 60_000)
    public void pollPendingConfirmations() {
        List<String> candidates;
        try {
            candidates = paymentRepository.findConfirmationCandidateReferences();
        } catch (Exception e) {
            log.error("Payment confirmation candidate scan failed: {}", e.getMessage(), e);
            schedulerHealthSignal.reportFailure("PaymentConfirmationScheduler.pollPendingConfirmations");
            return;
        }
        int processed = 0;
        for (String merchantReference : candidates) {
            try {
                paymentConfirmationOrchestrator.confirmPayment(merchantReference);
                processed++;
            } catch (Exception e) {
                log.error("Payment confirmation polling failed for merchantReference {}: {}",
                        merchantReference, e.getMessage(), e);
            }
        }
        if (processed > 0) {
            log.info("Payment confirmation poll: {} of {} candidate(s) processed", processed, candidates.size());
        }
        schedulerHealthSignal.reportSuccess("PaymentConfirmationScheduler.pollPendingConfirmations");
    }
}
