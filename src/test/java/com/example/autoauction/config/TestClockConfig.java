package com.example.autoauction.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public Clock testClock() {
        // Фиксированное время для тестов: 2024-01-01T00:00:00Z
        Instant fixedInstant = Instant.parse("2024-01-01T00:00:00Z");
        return Clock.fixed(fixedInstant, ZoneId.of("UTC"));
    }
}