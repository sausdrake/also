package com.example.autoauction.diagnostic.media.infrastructure.scheduler;

import com.example.autoauction.diagnostic.media.application.MediaReconciliationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * MEDIA-3.2. Thin orchestrator only, matching the FinancialRecoveryScheduler
 * convention (R-2) structurally - but a completely independent bean/schedule,
 * not reusing or depending on any financial scheduling machinery.
 */
@Slf4j
@Component
public class MediaReconciliationScheduler {

    private final MediaReconciliationService reconciliationService;

    public MediaReconciliationScheduler(MediaReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
        log.info("MediaReconciliationScheduler initialized");
    }

    @Scheduled(fixedDelay = 300_000)
    public void reconcileDiagnosticMedia() {
        try {
            reconciliationService.reconcile();
        } catch (Exception e) {
            log.error("Diagnostic media reconciliation sweep failed: {}", e.getMessage(), e);
        }
    }
}
