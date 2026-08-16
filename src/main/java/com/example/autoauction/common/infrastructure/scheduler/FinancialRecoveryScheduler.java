// common/infrastructure/scheduler/FinancialRecoveryScheduler.java
package com.example.autoauction.common.infrastructure.scheduler;

import com.example.autoauction.common.application.FinancialRecoveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * R-2: thin orchestrator only - all recovery business logic (verify-before-mutate,
 * verify-after-mutate, backoff, escalation) lives in FinancialRecoveryService.
 */
@Slf4j
@Component
public class FinancialRecoveryScheduler {

    private final FinancialRecoveryService recoveryService;

    public FinancialRecoveryScheduler(FinancialRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
        log.info("FinancialRecoveryScheduler initialized");
    }

    @Scheduled(fixedDelay = 60_000)
    public void recoverFailedFinancialOperations() {
        try {
            recoveryService.recoverPending();
        } catch (Exception e) {
            log.error("Financial recovery sweep failed: {}", e.getMessage(), e);
        }
    }
}
