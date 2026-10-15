package com.example.autoauction.auth.web;

import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import com.example.autoauction.user.domain.AdminUserAction;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.AdminUserActionRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AUTH-01: password recovery without email, end to end with real JWTs. An admin resets a KYC-verified
 * user's password to a one-time temporary password; the old password and sessions stop working; with
 * the temporary password the account can only change it; after the change the user signs in with the
 * new password and everything works again. The reset is journaled.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("AUTH-01: password reset by an administrator")
class PasswordResetIntegrationTest {

    private static final Long ADMIN_ID = 9951L;
    private static final String OLD_PASSWORD = "old-password-1";
    private static final AtomicInteger IP = new AtomicInteger(1);

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IdentityDocumentRepository identityDocumentRepository;
    @Autowired private IdentityVerificationRepository identityVerificationRepository;
    @Autowired private AdminUserActionRepository journal;

    @BeforeEach
    void roles() {
        roleRepository.findByName("ROLE_USER").orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "ROLE_USER")));
    }

    /** Login and registration are rate-limited per IP: every call comes from its own address. */
    private static RequestPostProcessor freshIp() {
        return request -> {
            request.setRemoteAddr("10.77." + (IP.get() / 250) + "." + (IP.getAndIncrement() % 250 + 1));
            return request;
        };
    }

    private static RequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(ADMIN_ID, "admin" + ADMIN_ID, "ADMIN"));
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private User user(boolean kycVerified) {
        Role role = roleRepository.findByName("ROLE_USER").orElseThrow();
        String name = "auth01u" + System.nanoTime();
        User user = userRepository.save(new User(null, name, name + "@test.com", passwordEncoder.encode(OLD_PASSWORD),
                true, Set.of(role), true, ADMIN_ID, OffsetDateTime.now()));
        if (kycVerified) {
            OffsetDateTime now = OffsetDateTime.now();
            Long doc = identityDocumentRepository.save(new IdentityDocument(
                    "identity-documents/auth01-" + user.getId() + "-" + System.nanoTime(), user.getId(), "image/jpeg", 1000L, now)).getId();
            IdentityVerification iv = IdentityVerification.firstSubmission(
                    user.getId(), "KG", DocumentType.NATIONAL_ID, "A1" + System.nanoTime(), doc, doc, now);
            iv.approve(now);
            identityVerificationRepository.save(iv);
        }
        return user;
    }

    private MvcResult login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").with(freshIp()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")).andReturn();
    }

    private String accessToken(String username, String password) throws Exception {
        MvcResult r = login(username, password);
        assertThat(r.getResponse().getStatus()).as("login of " + username).isEqualTo(200);
        return JsonPath.read(body(r), "$.accessToken");
    }

    private int getIdentity(String token) throws Exception {
        return mockMvc.perform(get("/api/v1/identity/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    private MvcResult reset(Long userId, RequestPostProcessor who) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/users/" + userId + "/password-reset").with(who)).andReturn();
    }

    private MvcResult changePassword(String token, String current, String next) throws Exception {
        return mockMvc.perform(post("/api/v1/account/password").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}")).andReturn();
    }

    @Test
    @DisplayName("full cycle: reset -> temporary password only changes the password -> new password works; journaled")
    void fullCycle() throws Exception {
        User user = user(true);
        MvcResult oldLogin = login(user.getUsername(), OLD_PASSWORD);
        String oldRefresh = JsonPath.read(body(oldLogin), "$.refreshToken");

        MvcResult reset = reset(user.getId(), admin());
        assertThat(reset.getResponse().getStatus()).isEqualTo(200);
        assertThat(reset.getResponse().getHeader("Cache-Control")).contains("no-store");
        String temporary = JsonPath.read(body(reset), "$.temporaryPassword");
        assertThat(temporary).hasSize(16).matches("[A-Za-z2-9]+");

        assertThat(login(user.getUsername(), OLD_PASSWORD).getResponse().getStatus()).as("old password").isEqualTo(401);
        assertThat(mockMvc.perform(post("/api/auth/refresh").with(freshIp()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + oldRefresh + "\"}")).andReturn().getResponse().getStatus())
                .as("old session's refresh token").isGreaterThanOrEqualTo(400);

        String temporaryToken = accessToken(user.getUsername(), temporary);
        MvcResult blocked = mockMvc.perform(get("/api/v1/identity/me").header("Authorization", "Bearer " + temporaryToken)).andReturn();
        assertThat(blocked.getResponse().getStatus()).isEqualTo(403);
        assertThat((String) JsonPath.read(body(blocked), "$.error")).isEqualTo("PASSWORD_CHANGE_REQUIRED");

        assertThat(changePassword(temporaryToken, temporary, "new-password-2").getResponse().getStatus()).isEqualTo(204);

        assertThat(login(user.getUsername(), temporary).getResponse().getStatus()).as("temporary password").isEqualTo(401);
        String newToken = accessToken(user.getUsername(), "new-password-2");
        assertThat(getIdentity(newToken)).isEqualTo(200);

        List<AdminUserAction> entries = journal.findByTargetUserIdNewestFirst(user.getId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).action().name()).isEqualTo("PASSWORD_RESET");
        assertThat(entries.get(0).adminId()).isEqualTo(ADMIN_ID);
        assertThat(entries.get(0).details()).as("the temporary password is never stored").isNull();

        User after = userRepository.findById(user.getId()).orElseThrow();
        assertThat(after.isActive()).isTrue();
        assertThat(after.isTrusted()).as("trust decision kept").isTrue();
    }

    @Test
    @DisplayName("a user without verified KYC cannot be reset: nothing changes, nothing journaled")
    void noKyc_refused() throws Exception {
        User user = user(false);
        assertThat(reset(user.getId(), admin()).getResponse().getStatus()).isBetween(400, 499);
        assertThat(login(user.getUsername(), OLD_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(journal.findByTargetUserIdNewestFirst(user.getId())).isEmpty();
    }

    @Test
    @DisplayName("only administrators can reset, and not their own password")
    void onlyAdmins_notSelf() throws Exception {
        User user = user(true);
        RequestPostProcessor plainUser = SecurityMockMvcRequestPostProcessors.user(
                TestUserPrincipal.createWithRoles(user.getId(), user.getUsername(), "USER"));
        assertThat(reset(user.getId(), plainUser).getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(post("/api/v1/admin/users/" + user.getId() + "/password-reset")).andReturn()
                .getResponse().getStatus()).isEqualTo(401);

        RequestPostProcessor self = SecurityMockMvcRequestPostProcessors.user(
                TestUserPrincipal.createWithRoles(user.getId(), user.getUsername(), "ADMIN"));
        assertThat(reset(user.getId(), self).getResponse().getStatus()).isBetween(400, 499);
        assertThat(login(user.getUsername(), OLD_PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("change password: wrong current password, too short or unchanged -> 400, password kept")
    void changePassword_validation() throws Exception {
        User user = user(true);
        String token = accessToken(user.getUsername(), OLD_PASSWORD);

        assertThat(changePassword(token, "wrong-password", "new-password-3").getResponse().getStatus()).isEqualTo(400);
        assertThat(changePassword(token, OLD_PASSWORD, "12345").getResponse().getStatus()).isEqualTo(400);
        assertThat(changePassword(token, OLD_PASSWORD, OLD_PASSWORD).getResponse().getStatus()).isEqualTo(400);
        assertThat(login(user.getUsername(), OLD_PASSWORD).getResponse().getStatus()).isEqualTo(200);

        assertThat(changePassword(token, OLD_PASSWORD, "new-password-3").getResponse().getStatus()).isEqualTo(204);
        assertThat(login(user.getUsername(), "new-password-3").getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(post("/api/v1/account/password").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn().getResponse().getStatus()).as("anonymous").isEqualTo(401);
    }
}
