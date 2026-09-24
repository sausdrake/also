package com.example.autoauction.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 70: HTTP-layer proof that {@code GET /api/health} is reachable without authentication -
 * required for the Docker container healthcheck (which has no JWT to present) to function at all.
 * {@code HealthController} itself is untouched; only {@code SecurityConfig}'s permitAll list
 * gained this one literal matcher.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Health endpoint - публично доступен без аутентификации")
class HealthControllerApiIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/health без токена -> 200, status=UP")
    void health_NoAuth_ReturnsUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("Auto Auction API"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
