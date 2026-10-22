package com.example.autoauction.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * API-DATE-01 (BL-25): REST and WebSocket share Spring Boot's ObjectMapper, so every date leaves the server as an
 * ISO-8601 string, and unknown request fields are still rejected.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("API-DATE-01 - one ObjectMapper, ISO-8601 dates on REST and WebSocket")
class ApiDateContractTest {

    private static final OffsetDateTime AT = OffsetDateTime.of(2026, 3, 4, 5, 6, 7, 123_000_000, ZoneOffset.UTC);

    record Payload(Long auctionId, OffsetDateTime endTime) {
    }

    @Autowired private ApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired @Qualifier("brokerMessageConverter") private MessageConverter brokerMessageConverter;

    @Test
    @DisplayName("exactly one ObjectMapper bean - Spring Boot's, configured by spring.jackson.*")
    void singleBootMapper() throws Exception {
        assertThat(context.getBeanNamesForType(ObjectMapper.class)).containsExactly("jacksonObjectMapper");
        assertThat(objectMapper.writeValueAsString(new Payload(1L, AT))).contains("\"endTime\":\"2026-03-04T05:06:07.123Z\"");
    }

    @Test
    @DisplayName("WebSocket messages carry ISO-8601 dates, not epoch seconds")
    void websocketDatesAreIso() {
        Message<?> message = brokerMessageConverter.toMessage(new Payload(7L, AT), null);
        assertThat(message).isNotNull();
        String json = new String((byte[]) message.getPayload(), StandardCharsets.UTF_8);

        assertThat(json).contains("\"endTime\"").contains("\"2026-03-04T05:06:07.123Z\"").doesNotContain("1772600767");
    }

    @Test
    @DisplayName("unknown request fields are still rejected")
    void unknownPropertiesRejected() {
        assertThat(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
        assertThatThrownBy(() -> objectMapper.readValue("{\"auctionId\":1,\"roles\":[\"ROLE_ADMIN\"]}", Payload.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    @Test
    @DisplayName("dates written as epoch seconds before this change are still read")
    void legacyEpochStillReadable() throws Exception {
        assertThat(objectMapper.readValue("{\"auctionId\":1,\"endTime\":1772600767.123000000}", Payload.class).endTime())
                .isAtSameInstantAs(AT);
    }
}
