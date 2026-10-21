package com.example.autoauction.admin.web;

import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BL-19 / BL-23: the admin user API exposes the registration time, and no admin action on an
 * existing user rewrites it. Real security chain and persistence (H2, "test" profile), same
 * harness as {@code AdminUserTrustApiIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("BL-19/BL-23: users.created_at is exposed to the admin and never rewritten by an update")
class AdminUserCreatedAtApiIntegrationTest {

    /** A registration time clearly in the past, so any "now" written by an update is visible. */
    private static final OffsetDateTime REGISTERED = OffsetDateTime.of(2025, 3, 4, 5, 6, 7, 0, ZoneOffset.UTC);

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private IdentityDocumentRepository identityDocumentRepository;
    @Autowired private IdentityVerificationRepository identityVerificationRepository;

    private Long userId;

    @BeforeEach
    void setUp() {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        roleRepository.findByName("ROLE_DIAGNOSTIC")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_DIAGNOSTIC", "Диагност")));
        String name = "bl19u" + System.nanoTime();
        userId = userRepository.save(new User(null, name, name + "@test.com", "encoded", true, Set.of(userRole))).getId();
        jdbcTemplate.update("UPDATE users SET created_at = ? WHERE id = ?", Timestamp.from(REGISTERED.toInstant()), userId);
    }

    private OffsetDateTime storedCreatedAt() {
        Timestamp ts = jdbcTemplate.queryForObject("SELECT created_at FROM users WHERE id = ?", Timestamp.class, userId);
        return ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    private void assertUnchanged(RequestBuilder action) throws Exception {
        String response = mockMvc.perform(action).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        if (response.contains("\"createdAt\"")) {
            // the returned user (built from the saved copy) carries the registration time too
            assertThat(toOffsetDateTime(JsonPath.read(response, "$.createdAt"))).isAtSameInstantAs(REGISTERED);
        }
        assertThat(storedCreatedAt()).isAtSameInstantAs(REGISTERED);
        assertThat(apiCreatedAt("$.createdAt", "/api/v1/admin/users/" + userId)).isAtSameInstantAs(REGISTERED);
    }

    private OffsetDateTime apiCreatedAt(String path, String url) throws Exception {
        String body = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Object value = JsonPath.read(body, path);
        if (value instanceof java.util.List<?> list) {
            assertThat(list).hasSize(1);
            value = list.get(0);
        }
        return toOffsetDateTime(value);
    }

    private static OffsetDateTime toOffsetDateTime(Object value) {
        // The REST ObjectMapper (WebSocketConfig bean) writes OffsetDateTime as epoch seconds, the
        // same shape every other date of this API has; the client's parseDate reads it.
        assertThat(value).as("createdAt in the admin user JSON").isInstanceOf(Number.class);
        java.math.BigDecimal seconds = new java.math.BigDecimal(value.toString());
        return java.time.Instant.ofEpochSecond(seconds.longValue(),
                seconds.remainder(java.math.BigDecimal.ONE).movePointRight(9).longValue()).atOffset(ZoneOffset.UTC);
    }

    @Test
    @WithMockCustomUser(userId = 42L, username = "admin", roles = {"ADMIN"})
    @DisplayName("GET /admin/users and /admin/users/{id} return createdAt")
    void createdAtExposed() throws Exception {
        assertThat(apiCreatedAt("$.createdAt", "/api/v1/admin/users/" + userId)).isAtSameInstantAs(REGISTERED);
        assertThat(apiCreatedAt("$[?(@.id == " + userId + ")].createdAt", "/api/v1/admin/users")).isAtSameInstantAs(REGISTERED);
    }

    @Test
    @WithMockCustomUser(userId = 42L, username = "admin", roles = {"ADMIN"})
    @DisplayName("trust / untrust / block / unblock / role change keep the registration time")
    void adminActionsKeepCreatedAt() throws Exception {
        assertUnchanged(patch("/api/v1/admin/users/" + userId + "/trust"));
        assertUnchanged(patch("/api/v1/admin/users/" + userId + "/untrust"));
        assertUnchanged(patch("/api/v1/admin/users/" + userId + "/block"));
        assertUnchanged(patch("/api/v1/admin/users/" + userId + "/unblock"));
        assertUnchanged(patch("/api/v1/admin/users/" + userId + "/role")
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ROLE_DIAGNOSTIC\"}"));
    }

    @Test
    @WithMockCustomUser(userId = 42L, username = "admin", roles = {"ADMIN"})
    @DisplayName("admin password reset keeps the registration time")
    void passwordResetKeepsCreatedAt() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Long doc = identityDocumentRepository.save(new IdentityDocument(
                "identity-documents/bl19-" + userId + "-" + System.nanoTime(), userId, "image/jpeg", 1000L, now)).getId();
        IdentityVerification iv = IdentityVerification.firstSubmission(
                userId, "KG", DocumentType.NATIONAL_ID, "B19" + System.nanoTime(), doc, doc, now);
        iv.approve(now);
        identityVerificationRepository.save(iv);

        assertUnchanged(post("/api/v1/admin/users/" + userId + "/password-reset"));
    }
}
