package com.example.autoauction.auth.web;

import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-01 (MVP certification E2E finding): a refresh token that was revoked by logout, rotated away or never existed
 * is an authentication failure - 401, not a 500 "internal server error"; a missing token is a 400. Real tokens
 * from /api/auth/login, no mocked principal.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("SEC-01: rejected refresh tokens answer 401, not 500")
class RefreshTokenRejectionStatusIntegrationTest {

    private static final String PASSWORD = "Refresh-" + UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static RequestPostProcessor fromIp(String ip) {
        return request -> { request.setRemoteAddr(ip); return request; };
    }

    private String loginRefreshToken(String ip) throws Exception {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String username = "refresh" + System.nanoTime();
        userRepository.save(new User(null, username, username + "@test.com", passwordEncoder.encode(PASSWORD), true, Set.of(role)));
        String body = mockMvc.perform(post("/api/auth/login").with(fromIp(ip)).contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.refreshToken");
    }

    private int send(String path, String json) throws Exception {
        return mockMvc.perform(post(path).with(fromIp("10.62.0.9")).contentType("application/json").content(json))
                .andReturn().getResponse().getStatus();
    }

    private static String token(String value) {
        return "{\"refreshToken\":\"" + value + "\"}";
    }

    @Test
    @DisplayName("refresh after logout -> 401; a second logout stays idempotent")
    void refreshAfterLogout() throws Exception {
        String refresh = loginRefreshToken("10.62.0.1");
        assertThat(send("/api/auth/logout", token(refresh))).isEqualTo(200);
        assertThat(send("/api/auth/refresh", token(refresh))).isEqualTo(401);
        assertThat(send("/api/auth/logout", token(refresh))).isEqualTo(200);
    }

    @Test
    @DisplayName("a rotated-away refresh token -> 401")
    void rotatedTokenRefused() throws Exception {
        String refresh = loginRefreshToken("10.62.0.2");
        assertThat(send("/api/auth/refresh", token(refresh))).isEqualTo(200);
        assertThat(send("/api/auth/refresh", token(refresh))).isEqualTo(401);
    }

    @Test
    @DisplayName("an unknown refresh token -> 401 on refresh and logout; an empty one -> 400")
    void unknownAndEmpty() throws Exception {
        assertThat(send("/api/auth/refresh", token("never-issued-" + UUID.randomUUID()))).isEqualTo(401);
        assertThat(send("/api/auth/logout", token("never-issued-" + UUID.randomUUID()))).isEqualTo(401);
        assertThat(send("/api/auth/refresh", token(""))).isIn(400);
    }
}
