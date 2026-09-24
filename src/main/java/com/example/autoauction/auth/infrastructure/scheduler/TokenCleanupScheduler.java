package com.example.autoauction.auth.infrastructure.scheduler;

import com.example.autoauction.admin.application.SchedulerHealthSignal;
import com.example.autoauction.auth.domain.port.RefreshTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableScheduling
public class TokenCleanupScheduler {

    private final RefreshTokenRepository refreshTokenRepository;
    private final SchedulerHealthSignal schedulerHealthSignal;

    public TokenCleanupScheduler(RefreshTokenRepository refreshTokenRepository, SchedulerHealthSignal schedulerHealthSignal) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.schedulerHealthSignal = schedulerHealthSignal;
    }

    /**
     * Очистка просроченных рефреш-токенов каждый день в 3:00 AM
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupExpiredTokens() {
        log.info("Starting cleanup of expired refresh tokens");
        try {
            refreshTokenRepository.deleteExpiredTokens();
            log.info("Expired refresh tokens cleaned up successfully");
            schedulerHealthSignal.reportSuccess("TokenCleanupScheduler.cleanupExpiredTokens");
        } catch (Exception e) {
            log.error("Error during token cleanup", e);
            schedulerHealthSignal.reportFailure("TokenCleanupScheduler.cleanupExpiredTokens");
        }
    }
}