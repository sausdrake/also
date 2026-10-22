package com.example.autoauction.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * PROD-SEC-01 / PROD-OPS-01 outside prod: the default security headers are sent, no Content-Security-Policy (the
 * development Swagger UI needs its scripts - prod sets a deny-all policy, ProdProfileBootPgIntegrationTest), and the
 * server shuts down gracefully in every profile.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("PROD-SEC-01: security headers and graceful shutdown outside prod")
class SecurityHeadersDefaultProfileTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ServerProperties serverProperties;

    @Test
    @DisplayName("default headers present, no CSP outside prod")
    void defaultHeadersWithoutCsp() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/health")).andReturn().getResponse();
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Content-Security-Policy")).isNull();
    }

    @Test
    @DisplayName("graceful shutdown is configured")
    void gracefulShutdown() {
        assertThat(serverProperties.getShutdown()).isEqualTo(Shutdown.GRACEFUL);
    }
}
