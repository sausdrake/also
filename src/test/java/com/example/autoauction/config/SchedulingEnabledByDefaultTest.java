package com.example.autoauction.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-01: moving {@code @EnableScheduling} into SchedulingConfig must not switch the jobs off
 * anywhere but in the PostgreSQL test profile - without the property every job is registered.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("TEST-01: scheduled jobs are registered by default")
class SchedulingEnabledByDefaultTest {

    @Autowired private List<ScheduledTaskHolder> holders;

    @Test
    void everyJobRegistered() {
        List<String> tasks = holders.stream().flatMap(h -> h.getScheduledTasks().stream())
                .map(t -> t.getTask().getRunnable().toString()).toList();
        assertThat(tasks).anyMatch(t -> t.contains("AuctionCompletionScheduler.completeExpiredAuctions"));
        assertThat(tasks).anyMatch(t -> t.contains("TokenCleanupScheduler"));
        assertThat(tasks).anyMatch(t -> t.contains("PaymentConfirmationScheduler"));
        assertThat(tasks).anyMatch(t -> t.contains("FinancialRecoveryScheduler"));
        assertThat(tasks).hasSizeGreaterThanOrEqualTo(10);
    }
}
