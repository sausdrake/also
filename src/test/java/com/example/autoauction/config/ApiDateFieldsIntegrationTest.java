package com.example.autoauction.config;

import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.deposit.domain.WithdrawalRequestStatus;
import com.example.autoauction.deposit.web.dto.WithdrawalRequestResponse;
import com.example.autoauction.notification.application.NotificationService;
import com.example.autoauction.notification.domain.Notification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-DATE-01: the date fields the client drops unless they are strings (notification.ts, ProfilePage withdrawals)
 * now arrive as ISO-8601 strings - over REST and in the WebSocket push.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("API-DATE-01 - notification and withdrawal dates are ISO strings")
class ApiDateFieldsIntegrationTest {

    private static final String ISO_LOCAL = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?";

    @Autowired private MockMvc mockMvc;
    @Autowired private NotificationService notificationService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired @Qualifier("brokerMessageConverter") private MessageConverter brokerMessageConverter;

    @Test
    @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
    @DisplayName("GET /api/v1/notifications: createdAt is an ISO string")
    void notificationRest() throws Exception {
        notificationService.send(1L, "SYSTEM", "API-DATE-01", "date check");

        String body = mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Object createdAt = JsonPath.read(body, "$.content[0].createdAt");

        assertThat(createdAt).isInstanceOf(String.class);
        assertThat((String) createdAt).matches(ISO_LOCAL);
    }

    @Test
    @DisplayName("WebSocket notification push: createdAt is an ISO string")
    void notificationPush() throws Exception {
        Notification n = new Notification(1L, "SYSTEM", "t", "m");
        n.setCreatedAt(LocalDateTime.of(2026, 3, 4, 5, 6, 7));
        byte[] payload = (byte[]) brokerMessageConverter.toMessage(n, null).getPayload();
        JsonNode json = objectMapper.readTree(new String(payload, StandardCharsets.UTF_8));

        assertThat(json.get("createdAt").isTextual()).isTrue();
        assertThat(json.get("createdAt").asText()).isEqualTo("2026-03-04T05:06:07");
    }

    @Test
    @DisplayName("withdrawal response: createdAt / processedAt are ISO strings with offset")
    void withdrawalDates() throws Exception {
        OffsetDateTime at = OffsetDateTime.of(2026, 3, 4, 5, 6, 7, 0, ZoneOffset.ofHours(6));
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(new WithdrawalRequestResponse(1L, 2L, null, null,
                new BigDecimal("10.00"), WithdrawalRequestStatus.PAID, at, at, null, at.plusHours(1), null, null, null)));

        assertThat(json.get("createdAt").asText()).isEqualTo("2026-03-04T05:06:07+06:00");
        assertThat(json.get("processedAt").asText()).isEqualTo("2026-03-04T06:06:07+06:00");
        assertThat(json.get("amount").decimalValue()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("OpenAPI (dev only, disabled in prod) documents the same shape: string / date-time")
    void openApiMatches() throws Exception {
        String docs = mockMvc.perform(get("/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode createdAt = objectMapper.readTree(docs).at("/components/schemas/WithdrawalRequestResponse/properties/createdAt");

        assertThat(createdAt.get("type").asText()).isEqualTo("string");
        assertThat(createdAt.get("format").asText()).isEqualTo("date-time");
    }
}
