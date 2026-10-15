package com.example.autoauction.config;

import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ERR-01: a request the client got wrong (unreadable JSON, wrong method, wrong content type) is a
 * 4xx with a generic message - not a 500 "internal error", and the parser's text (class names,
 * field names, input fragments) never reaches the client. Nothing happens before the body is read,
 * so such a request has no side effects.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ERR-01: client errors are 4xx, not 500")
class ClientErrorMappingIntegrationTest {

    private static final Long USER_ID = 9901L;
    private static final Long ADMIN_ID = 9902L;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long payments() {
        return jdbcTemplate.queryForObject("select count(*) from payment", Long.class);
    }

    private static RequestPostProcessor as(Long id, String role) {
        return SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(id, "u" + id, role));
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private MvcResult postJson(String url, String json, RequestPostProcessor who) throws Exception {
        return mockMvc.perform(post(url).with(who).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private static void assertGenericBadRequest(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        String body = body(result);
        assertThat((Integer) JsonPath.read(body, "$.status")).isEqualTo(400);
        assertThat((String) JsonPath.read(body, "$.error")).isEqualTo("Некорректное тело запроса");
        assertThat(body).doesNotContain("com.example", "jackson", "JSON parse", "Unrecognized", "line:");
    }

    @Test
    @DisplayName("malformed JSON -> 400, nothing created")
    void malformedJson_400() throws Exception {
        long before = payments();
        assertGenericBadRequest(postJson("/api/v1/payments", "{\"amount\": 1000,", as(USER_ID, "USER")));
        assertThat(payments()).isEqualTo(before);
    }

    @Test
    @DisplayName("an empty body where one is required -> 400")
    void emptyBody_400() throws Exception {
        assertGenericBadRequest(postJson("/api/v1/payments", "", as(USER_ID, "USER")));
    }

    @Test
    @DisplayName("an unknown field -> 400 (the request is still rejected as a whole), nothing created")
    void unknownField_400() throws Exception {
        long before = payments();
        assertGenericBadRequest(postJson("/api/v1/payments",
                "{\"amount\": 1000, \"idempotencyKey\": \"err01-k\", \"userId\": 777777}", as(USER_ID, "USER")));
        assertThat(payments()).isEqualTo(before);
    }

    @Test
    @DisplayName("an unknown enum value (expectedStatus: \"pending\") on a withdrawal reject -> 400")
    void unknownEnum_400() throws Exception {
        assertGenericBadRequest(postJson("/api/v1/admin/withdrawals/" + Long.MAX_VALUE + "/reject",
                "{\"reason\": \"x\", \"payoutNotMade\": true, \"expectedStatus\": \"pending\"}", as(ADMIN_ID, "ADMIN")));
    }

    @Test
    @DisplayName("a value of the wrong JSON type (amount: \"abc\") -> 400, nothing created")
    void wrongJsonType_400() throws Exception {
        long before = payments();
        assertGenericBadRequest(postJson("/api/v1/payments",
                "{\"amount\": \"abc\", \"idempotencyKey\": \"err01-t\"}", as(USER_ID, "USER")));
        assertThat(payments()).isEqualTo(before);
    }

    @Test
    @DisplayName("a wrong content type -> 415")
    void wrongContentType_415() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/payments").with(as(USER_ID, "USER"))
                .contentType(MediaType.TEXT_PLAIN).content("amount=1000")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(415);
        assertThat((Integer) JsonPath.read(body(result), "$.status")).isEqualTo(415);
    }

    @Test
    @DisplayName("a wrong HTTP method -> 405")
    void wrongMethod_405() throws Exception {
        MvcResult result = mockMvc.perform(delete("/api/v1/payments").with(as(USER_ID, "USER"))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(405);
        assertThat((Integer) JsonPath.read(body(result), "$.status")).isEqualTo(405);
    }

    @Test
    @DisplayName("a real server failure is still a 500 (the generic handler is untouched)")
    void serverFailure_still500() throws Exception {
        // payoutNotMade/expectedStatus valid, but the withdrawal does not exist: a domain error, never 2xx
        MvcResult result = postJson("/api/v1/admin/withdrawals/" + Long.MAX_VALUE + "/reject",
                "{\"reason\": \"x\", \"payoutNotMade\": true, \"expectedStatus\": \"PENDING\"}", as(ADMIN_ID, "ADMIN"));
        assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
    }
}
