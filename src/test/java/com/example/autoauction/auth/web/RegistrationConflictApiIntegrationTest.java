package com.example.autoauction.auth.web;

import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-AUTH-01: HTTP contract of a duplicate registration. Before the fix AuthService threw a bare
 * RuntimeException("Username already exists") and the client got 500. Now: 409 with ONE public
 * code and message for a taken username, a taken email or both (anti-enumeration - the body never
 * says which), nothing is created or overwritten, and login/registration keep working. Every
 * request comes from its own fake address so the IP-keyed register limiter never interferes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("BE-AUTH-01: duplicate registration -> 409, one anti-enumeration body")
class RegistrationConflictApiIntegrationTest {

    private static final String PUBLIC_MESSAGE = "Пользователь с указанными данными уже существует.";
    private static final AtomicInteger IP = new AtomicInteger(1);

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private ObjectMapper objectMapper;

    /** The "test" profile has no Flyway seed: registration needs ROLE_USER (as AuthRateLimitApiIntegrationTest does). */
    @BeforeEach
    void ensureUserRole() {
        if (roleRepository.findByName("ROLE_USER").isEmpty()) {
            roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь"));
        }
    }

    private static RequestPostProcessor freshIp() {
        int n = IP.getAndIncrement();
        String ip = "10.77." + (n / 250) + "." + (n % 250 + 1);
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private ResultActions registerRaw(String json) throws Exception {
        return mockMvc.perform(post("/api/auth/register").with(freshIp()).contentType("application/json").content(json));
    }

    private ResultActions register(String username, String email, String password) throws Exception {
        return registerRaw(String.format("{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}", username, email, password));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").with(freshIp()).contentType("application/json")
                .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)));
    }

    private static String unique(String prefix) {
        return prefix + (System.nanoTime() % 1_000_000_000L);
    }

    /** The conflict body without its timestamp - what a client can compare between two answers. */
    private JsonNode conflictBody(ResultActions result) throws Exception {
        String raw = result.andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        ObjectNode body = (ObjectNode) objectMapper.readTree(raw);
        body.remove("timestamp");
        return body;
    }

    private static void assertNoInternals(String raw) {
        assertThat(raw).doesNotContainIgnoringCase("exception")
                .doesNotContain("already exists")
                .doesNotContain("USERNAME_TAKEN").doesNotContain("EMAIL_TAKEN")
                .doesNotContain("users_username_key").doesNotContain("users_email_key")
                .doesNotContain("\"field\"");
    }

    @Test
    @DisplayName("taken username, taken email and both -> byte-identical 409 bodies (no enumeration)")
    void identicalBodiesForEveryConflict() throws Exception {
        String username = unique("dupuser");
        String email = username + "@test.com";
        register(username, email, "password123").andExpect(status().isOk());

        JsonNode byUsername = conflictBody(register(username, unique("other") + "@test.com", "password456"));
        JsonNode byEmail = conflictBody(register(unique("other"), email, "password456"));
        JsonNode byBoth = conflictBody(register(username, email, "password456"));

        assertThat(byUsername.get("status").asInt()).isEqualTo(409);
        assertThat(byUsername.get("code").asText()).isEqualTo("REGISTRATION_CONFLICT");
        assertThat(byUsername.get("message").asText()).isEqualTo(PUBLIC_MESSAGE);
        assertThat(byUsername.has("field")).isFalse();
        assertThat(byEmail).isEqualTo(byUsername);
        assertThat(byBoth).isEqualTo(byUsername);
        assertNoInternals(byUsername.toString());
    }

    @Test
    @DisplayName("taken email with a new username creates no user")
    void takenEmailCreatesNothing() throws Exception {
        String email = unique("dupmail") + "@test.com";
        register(unique("first"), email, "password123").andExpect(status().isOk());
        String second = unique("second");

        register(second, email, "password123").andExpect(status().isConflict());

        assertThat(userRepository.findByUsername(second)).isEmpty();
    }

    @Test
    @DisplayName("adversarial: a duplicate with another password neither overwrites the account nor logs in")
    void duplicateDoesNotOverwriteAccount() throws Exception {
        String username = unique("victim");
        register(username, username + "@test.com", "originalPass1").andExpect(status().isOk());
        String hashBefore = userRepository.findByUsername(username).orElseThrow().getPasswordHash();

        register(username, username + "@test.com", "attackerPass1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());

        assertThat(userRepository.findByUsername(username).orElseThrow().getPasswordHash()).isEqualTo(hashBefore);
        login(username, "attackerPass1").andExpect(status().isUnauthorized());
        login(username, "originalPass1").andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").exists());
    }

    @Test
    @DisplayName("adversarial: the same duplicate repeated stays 409 with the same body")
    void repeatedDuplicateIsStable() throws Exception {
        String username = unique("repeat");
        register(username, username + "@test.com", "password123").andExpect(status().isOk());
        JsonNode first = conflictBody(register(username, username + "@test.com", "password123"));
        for (int i = 0; i < 3; i++) {
            assertThat(conflictBody(register(username, username + "@test.com", "password123"))).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("auth is not broken: a fresh registration after conflicts still succeeds with tokens")
    void freshRegistrationStillWorks() throws Exception {
        String username = unique("taken");
        register(username, username + "@test.com", "password123").andExpect(status().isOk());
        register(username, username + "@test.com", "password123").andExpect(status().isConflict());

        String fresh = unique("fresh");
        register(fresh, fresh + "@test.com", "password123")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
    }

    @Test
    @DisplayName("adversarial: malformed JSON, missing fields and over-long input are 400, never 409/500")
    void badInputIsNotAConflict() throws Exception {
        String malformed = registerRaw("{\"username\":\"abc\",").andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertNoInternals(malformed);

        registerRaw("{}").andExpect(status().isBadRequest());
        registerRaw("{\"username\":\"onlyname123\"}").andExpect(status().isBadRequest());
        registerRaw("{\"email\":\"only@test.com\",\"password\":\"password123\"}").andExpect(status().isBadRequest());

        String longName = "a".repeat(5000);
        String longRaw = register(longName, unique("long") + "@test.com", "password123").andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertNoInternals(longRaw);
        register(unique("longmail"), "a".repeat(5000) + "@test.com", "password123").andExpect(status().isBadRequest());
        assertThat(userRepository.findByUsername(longName)).isEmpty();
    }

    @Test
    @DisplayName("adversarial: registering grants no access to protected endpoints without a token")
    void noAccessWithoutToken() throws Exception {
        String username = unique("noaccess");
        register(username, username + "@test.com", "password123").andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/auctions/all").with(freshIp())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/deposits/my").with(freshIp())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/diagnostic/work/pending").with(freshIp())).andExpect(status().isUnauthorized());
    }
}
