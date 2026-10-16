package com.example.autoauction.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-01: in the shared-database PostgreSQL test context no background job runs - nothing can
 * touch another test class's rows behind its back.
 */
@DisplayName("TEST-01: no scheduled jobs in the PostgreSQL test context")
@org.testcontainers.junit.jupiter.Testcontainers(disabledWithoutDocker = true)
class SchedulingDisabledPgIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired private ApplicationContext context;

    @Test
    void noScheduledTasks() {
        long tasks = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .mapToLong(h -> h.getScheduledTasks().size()).sum();
        assertThat(tasks).isZero();
    }
}
