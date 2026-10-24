package com.example.autoauction.auth.infrastructure.security;

import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * JWT-TYPE-01 adversarial: access and refresh tokens are no longer interchangeable. Before the fix a refresh
 * token sent as {@code Authorization: Bearer} reached protected endpoints (live on the demo stand, 200), for its
 * 7-day lifetime and even after logout.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("JWT-TYPE-01: refresh tokens never authenticate API requests")
class JwtTokenTypeIntegrationTest {

    private static final String PROTECTED = "/api/notifications/unread-count";

    @Autowired private MockMvc mockMvc;
    @Autowired private RoleRepository roleRepository;
    @Value("${jwt.secret}") private String secret;

    private String access;
    private String refresh;
    private String username;

    @BeforeEach
    void register() throws Exception {
        roleRepository.findByName("ROLE_USER").orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "USER")));
        username = "jwt" + System.nanoTime();
        MvcResult r = mockMvc.perform(post("/api/auth/register").with(req -> { req.setRemoteAddr("10.77." + (System.nanoTime() % 200) + ".1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username + "@t.test\",\"password\":\"jwt-password-1\"}"))
                .andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(200);
        access = JsonPath.read(body, "$.accessToken");
        refresh = JsonPath.read(body, "$.refreshToken");
    }

    private int status(String bearer) throws Exception {
        return mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + bearer)).andReturn().getResponse().getStatus();
    }

    private int refreshCall(String token) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + token + "\"}")).andReturn().getResponse().getStatus();
    }

    private String signed(Map<String, Object> claims, Date expiry) {
        return Jwts.builder().setClaims(claims).setSubject(username).setIssuedAt(new Date())
                .setExpiration(expiry).signWith(Keys.hmacShaKeyFor(secret.getBytes()), SignatureAlgorithm.HS256).compact();
    }

    @Test
    @DisplayName("valid access token -> 200; the refresh token as Bearer -> 401")
    void accessYesRefreshNo() throws Exception {
        assertThat(status(access)).isEqualTo(200);
        assertThat(status(refresh)).isEqualTo(401);
    }

    @Test
    @DisplayName("refresh endpoint accepts only the refresh token, and only once (rotation)")
    void refreshEndpoint() throws Exception {
        assertThat(refreshCall(access)).as("an access token is not a refresh token").isEqualTo(401);
        assertThat(refreshCall(refresh)).isEqualTo(200);
        assertThat(refreshCall(refresh)).as("rotated: the old refresh token is revoked").isEqualTo(401);
    }

    @Test
    @DisplayName("after logout the refresh token works neither as Bearer nor at the refresh endpoint")
    void afterLogout() throws Exception {
        int logout = mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refresh + "\"}")).andReturn().getResponse().getStatus();
        assertThat(logout).isBetween(200, 204);
        assertThat(status(refresh)).isEqualTo(401);
        assertThat(refreshCall(refresh)).isEqualTo(401);
    }

    @Test
    @DisplayName("expired access, legacy untyped, refresh-typed, forged and garbage tokens -> 401")
    void rejectedTokens() throws Exception {
        Date past = new Date(System.currentTimeMillis() - 60_000);
        Date future = new Date(System.currentTimeMillis() + 3_600_000);
        String jti = UUID.randomUUID().toString();
        assertThat(status(signed(Map.of("token_type", "access", "jti", jti), past))).as("expired access").isEqualTo(401);
        assertThat(status(signed(Map.of("jti", jti), future))).as("legacy token without a type").isEqualTo(401);
        assertThat(status(signed(Map.of("token_type", "refresh", "jti", jti), future))).as("refresh type").isEqualTo(401);
        assertThat(status(signed(Map.of("token_type", "ACCESS", "jti", jti), future))).as("type spelled differently").isEqualTo(401);
        String forged = Jwts.builder().setClaims(Map.of("token_type", "access")).setSubject(username).setExpiration(future)
                .signWith(Keys.hmacShaKeyFor("another-secret-another-secret-another-secret!!".getBytes()), SignatureAlgorithm.HS256).compact();
        assertThat(status(forged)).as("forged signature").isEqualTo(401);
        assertThat(status("not.a.jwt")).as("garbage").isEqualTo(401);
        assertThat(refreshCall(signed(Map.of("token_type", "refresh", "jti", jti), past))).as("expired refresh").isEqualTo(401);
    }

    @Test
    @DisplayName("a public page is served anonymously when a stale or invalid token is sent along")
    void publicPageWithBadToken() throws Exception {
        for (String bad : new String[]{"not.a.jwt", refresh,
                signed(Map.of("token_type", "access"), new Date(System.currentTimeMillis() - 60_000))}) {
            int code = mockMvc.perform(get("/api/v1/public/auctions").header("Authorization", "Bearer " + bad))
                    .andReturn().getResponse().getStatus();
            assertThat(code).isEqualTo(200);
        }
    }
}
