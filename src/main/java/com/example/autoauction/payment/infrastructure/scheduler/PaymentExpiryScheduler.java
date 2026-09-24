package com.example.autoauction.payment.infrastructure.scheduler;

import com.example.autoauction.admin.application.SchedulerHealthSignal;
import com.example.autoauction.payment.application.PaymentExpiryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * P1.5: trigger only, exactly like {@code FinancialRecoveryScheduler} - no expiry business logic
 * lives here. Calls {@link PaymentExpiryService} (a separate Spring bean) once per candidate id
 * so each candidate gets its own independent transaction (see that service's Javadoc for why);
 * a failure processing one candidate is logged and never aborts the rest of the sweep.
 */
@Slf4j
@Component
public class PaymentExpiryScheduler {

    private final PaymentExpiryService paymentExpiryService;
    private final SchedulerHealthSignal schedulerHealthSignal;

    public PaymentExpiryScheduler(PaymentExpiryService paymentExpiryService, SchedulerHealthSignal schedulerHealthSignal) {
        this.paymentExpiryService = paymentExpiryService;
        this.schedulerHealthSignal = schedulerHealthSignal;
        log.info("PaymentExpiryScheduler initialized");
    }

    @Scheduled(fixedDelay = 60_000)
    public void sweepExpiredPayments() {
        List<Long> candidates;
        try {
            candidates = paymentExpiryService.findExpiredCandidateIds();
        } catch (Exception e) {
            log.error("Payment expiry candidate scan failed: {}", e.getMessage(), e);
            schedulerHealthSignal.reportFailure("PaymentExpiryScheduler.sweepExpiredPayments");
            return;
        }
        int expired = 0;
        for (Long id : candidates) {
            try {
                if (paymentExpiryService.expireOnePayment(id)) {
                    expired++;
                }
            } catch (Exception e) {
                log.error("Payment expiry failed for payment {}: {}", id, e.getMessage(), e);
            }
        }
        if (expired > 0) {
            log.info("Payment expiry sweep: {} of {} candidate(s) expired", expired, candidates.size());
        }
        schedulerHealthSignal.reportSuccess("PaymentExpiryScheduler.sweepExpiredPayments");
    }
}
