package com.example.autoauction.identity.infrastructure.scheduler;

import com.example.autoauction.admin.application.SchedulerHealthSignal;
import com.example.autoauction.identity.application.IdentityCaptureCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** KYC-SLOT-01: hourly removal of abandoned identity capture slots (objects in private storage + rows). */
@Slf4j
@Component
public class IdentityCaptureCleanupScheduler {

    private final IdentityCaptureCleanupService cleanup;
    private final SchedulerHealthSignal health;

    public IdentityCaptureCleanupScheduler(IdentityCaptureCleanupService cleanup, SchedulerHealthSignal health) {
        this.cleanup = cleanup;
        this.health = health;
    }

    @Scheduled(cron = "0 17 * * * ?")
    public void removeAbandonedCaptures() {
        try {
            cleanup.removeAbandonedCaptures();
            health.reportSuccess("IdentityCaptureCleanupScheduler.removeAbandonedCaptures");
        } catch (Exception e) {
            log.error("Identity capture cleanup failed: {}", e.getClass().getSimpleName());
            health.reportFailure("IdentityCaptureCleanupScheduler.removeAbandonedCaptures");
        }
    }
}
