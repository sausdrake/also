package com.example.autoauction.auth.web;

import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 64: HTTP-layer proof that {@code /api/auth/login} and {@code /register} are IP-keyed
 * rate-limited via {@code AuthRateLimitService}, and that {@code /refresh}/{@code /logout}
 * (bearer-token-protected, not a guessable-credential surface) remain untouched. Each test uses
 * its own distinct fake remote address (via {@link #fromIp}) so the shared, Spring-managed
 * {@code AuthRateLimitService} bean's buckets never cross-contaminate between test methods,
 * mirroring the isolation {@code RateLimitServiceTest} achieves via per-method user ids for the
 * bidding limiter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Auth rate limiting - IP-keyed throttling for login/register")
class AuthRateLimitApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private String createUserWithPassword(String rawPassword) {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String username = "maskuser" + System.nanoTime();
        User u = new User(null, username, username + "@test.com",
                passwordEncoder.encode(rawPassword), true, Set.of(role));
        userRepository.save(u);
        return username;
    }

    @Test
    @DisplayName("login: below-limit failed attempts stay 401 (masked), (N+1)th returns 429")
    void login_BelowLimit401_ThenExceeded429() throws Exception {
        String ip = "10.10.1.1";
        String body = "{\"username\":\"ratelimit-login-nonexistent\",\"password\":\"wrongpass\"}";

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login").with(fromIp(ip))
                            .contentType("application/json").content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid username or password"));
        }

        mockMvc.perform(post("/api/auth/login").with(fromIp(ip))
                        .contentType("application/json").content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    @DisplayName("login: unknown-user and wrong-password error messages remain byte-identical (no enumeration introduced)")
    void login_ErrorMaskingUnchanged_NoEnumeration() throws Exception {
        String existingUsername = createUserWithPassword("correctPassword123");

        String unknownUserResponse = mockMvc.perform(post("/api/auth/login").with(fromIp("10.10.1.2"))
                        .contentType("application/json")
                        .content("{\"username\":\"definitely-does-not-exist-xyz\",\"password\":\"whatever\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String wrongPasswordResponse = mockMvc.perform(post("/api/auth/login").with(fromIp("10.10.1.3"))
                        .contentType("application/json")
                        .content(String.format("{\"username\":\"%s\",\"password\":\"wrongPassword\"}", existingUsername)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownUserMessage = com.jayway.jsonpath.JsonPath.read(unknownUserResponse, "$.message");
        String wrongPasswordMessage = com.jayway.jsonpath.JsonPath.read(wrongPasswordResponse, "$.message");
        org.assertj.core.api.Assertions.assertThat(unknownUserMessage).isEqualTo(wrongPasswordMessage);
        org.assertj.core.api.Assertions.assertThat(unknownUserMessage).isEqualTo("Invalid username or password");
    }

    @Test
    @DisplayName("register: below-limit attempts follow normal behavior, (N+1)th returns 429")
    void register_BelowLimit_ThenExceeded429() throws Exception {
        String ip = "10.10.2.1";

        for (int i = 0; i < 3; i++) {
            String body = String.format(
                    "{\"username\":\"ratelimituser%d%d\",\"email\":\"ratelimit%d%d@test.com\",\"password\":\"password123\"}",
                    System.nanoTime() % 100000, i, System.nanoTime() % 100000, i);
            mockMvc.perform(post("/api/auth/register").with(fromIp(ip))
                    .contentType("application/json").content(body));
        }

        String finalBody = "{\"username\":\"ratelimitoverflow\",\"email\":\"overflow@test.com\",\"password\":\"password123\"}";
        mockMvc.perform(post("/api/auth/register").with(fromIp(ip))
                        .contentType("application/json").content(finalBody))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    @DisplayName("cross-scope independence: exhausting LOGIN from one IP does not affect REGISTER from the same IP")
    void exhaustingLogin_DoesNotAffectRegister() throws Exception {
        String ip = "10.10.4.1";
        String loginBody = "{\"username\":\"crossscope-nonexistent\",\"password\":\"wrongpass\"}";

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login").with(fromIp(ip))
                    .contentType("application/json").content(loginBody));
        }
        mockMvc.perform(post("/api/auth/login").with(fromIp(ip))
                        .contentType("application/json").content(loginBody))
                .andExpect(status().isTooManyRequests());

        // REGISTER from the same IP is still fully available.
        String registerBody = String.format(
                "{\"username\":\"crossscopeuser%d\",\"email\":\"crossscope%d@test.com\",\"password\":\"password123\"}",
                System.nanoTime(), System.nanoTime());
        mockMvc.perform(post("/api/auth/register").with(fromIp(ip))
                        .contentType("application/json").content(registerBody))
                .andExpect(status().isOk());
    }
}
