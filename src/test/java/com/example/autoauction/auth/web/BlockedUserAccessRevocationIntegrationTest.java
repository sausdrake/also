package com.example.autoauction.auth.web;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.user.application.UserService;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-01: blocking a user must revoke access at once - not when the 24 h access token expires.
 * Uses real tokens from /api/auth/login (no mocked principal), because the defect lived exactly
 * there: the UserDetails built from the user always reported enabled=true.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("SEC-01: a blocked user loses REST access immediately")
class BlockedUserAccessRevocationIntegrationTest {

    private static final Long ADMIN_ID = 9601L;
    private static final String PASSWORD = "Sec01-" + UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserService userService;
    @Autowired private DepositService depositService;

    private static RequestPostProcessor fromIp(String ip) {
        return request -> { request.setRemoteAddr(ip); return request; };
    }

    private User newUser() {
        Role role = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        String username = "sec01u" + System.nanoTime();
        User saved = userRepository.save(new User(null, username, username + "@test.com",
                passwordEncoder.encode(PASSWORD), true, Set.of(role)));
        depositService.createDeposit(saved.getId(), new BigDecimal("1000"));
        return saved;
    }

    private String[] login(String username, String ip) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").with(fromIp(ip)).contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new String[]{JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken")};
    }

    private int getMyDeposit(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/v1/deposits/my").header("Authorization", "Bearer " + accessToken))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("an access token issued before the block is refused on every REST call after it")
    void blockedUser_existingAccessTokenRefused() throws Exception {
        User user = newUser();
        String[] tokens = login(user.getUsername(), "10.61.0.1");
        assertThat(getMyDeposit(tokens[0])).isEqualTo(200);

        userService.blockUser(user.getId(), ADMIN_ID);

        assertThat(getMyDeposit(tokens[0])).as("read endpoint").isEqualTo(401);
        int withdrawal = mockMvc.perform(post("/api/v1/withdrawals").header("Authorization", "Bearer " + tokens[0])
                        .contentType("application/json")
                        .content("{\"amount\":100,\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(withdrawal).as("money endpoint").isEqualTo(401);
        int bid = mockMvc.perform(post("/api/auctions/1/bids").header("Authorization", "Bearer " + tokens[0])
                        .contentType("application/json").content("{\"amount\":1000}"))
                .andReturn().getResponse().getStatus();
        assertThat(bid).as("bidding endpoint").isEqualTo(401);
        assertThat(depositService.getDepositByUserId(user.getId()).balance()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("a blocked user can neither refresh nor log in again (401, not 500)")
    void blockedUser_noRefreshNoLogin() throws Exception {
        User user = newUser();
        String[] tokens = login(user.getUsername(), "10.61.0.2");

        userService.blockUser(user.getId(), ADMIN_ID);

        int refresh = mockMvc.perform(post("/api/auth/refresh").contentType("application/json")
                        .content("{\"refreshToken\":\"" + tokens[1] + "\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(refresh).as("refresh").isBetween(400, 499);
        int relogin = mockMvc.perform(post("/api/auth/login").with(fromIp("10.61.0.3")).contentType("application/json")
                        .content("{\"username\":\"" + user.getUsername() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(relogin).as("login of a blocked user").isEqualTo(401);
    }

    @Test
    @DisplayName("unblocking restores access; other users are unaffected throughout")
    void unblock_restoresAccess_othersUnaffected() throws Exception {
        User blocked = newUser();
        User other = newUser();
        String[] blockedTokens = login(blocked.getUsername(), "10.61.0.4");
        String[] otherTokens = login(other.getUsername(), "10.61.0.5");

        userService.blockUser(blocked.getId(), ADMIN_ID);
        assertThat(getMyDeposit(blockedTokens[0])).isEqualTo(401);
        assertThat(getMyDeposit(otherTokens[0])).isEqualTo(200);

        userService.unblockUser(blocked.getId(), ADMIN_ID);
        assertThat(getMyDeposit(blockedTokens[0])).isEqualTo(200);
    }
}
