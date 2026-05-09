package com.example.autoauction.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        // Для production используем системные часы
        return Clock.systemUTC();
    }
}