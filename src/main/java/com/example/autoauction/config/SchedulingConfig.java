package com.example.autoauction.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TEST-01: the single switch for the application's {@code @Scheduled} jobs (auction completion,
 * payment confirmation/expiry/recovery, financial recovery, media, token cleanup). On unless
 * {@code also.scheduling.enabled=false} - which only the PostgreSQL test profile sets: its test
 * classes share one database, and background jobs of a cached context would otherwise act on rows
 * another test class left behind, in the middle of an unrelated test.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "also.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
