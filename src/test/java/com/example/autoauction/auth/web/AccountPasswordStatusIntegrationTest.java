package com.example.autoauction.auth.web;

import com.example.autoauction.auth.application.ExternalIdentityResolver;
import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AUTH-TG-UX-01: a Telegram-only account has no password. The profile asks whether one exists (and learns
 * nothing else); changing a password the account does not have is refused plainly, not as "wrong password",
 * and changes nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("AUTH-TG-UX-01: password status of passwordless (Telegram-only) accounts")
class AccountPasswordStatusIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void roles() {
        roleRepository.findByName("ROLE_USER").orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "ROLE_USER")));
    }

    private User user(String passwordHash) {
        Role role = roleRepository.findByName("ROLE_USER").orElseThrow();
        String name = "pwst" + System.nanoTime();
        return userRepository.save(new User(null, name, null, passwordHash, true, Set.of(role)));
    }

    private static RequestPostProcessor as(User u) {
        return SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(u.getId(), u.getUsername(), "USER"));
    }

    @Test
    @DisplayName("Telegram-only account: passwordSet=false; the response carries nothing else")
    void telegramOnly_noPassword() throws Exception {
        User u = user(ExternalIdentityResolver.NO_PASSWORD);

        String body = mockMvc.perform(get("/api/v1/account/password").with(as(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordSet").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).isEqualTo("{\"passwordSet\":false}");
    }

    @Test
    @DisplayName("password account: passwordSet=true, the hash never leaves the server")
    void passwordAccount() throws Exception {
        User u = user(passwordEncoder.encode("secret-pass-1"));

        String body = mockMvc.perform(get("/api/v1/account/password").with(as(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordSet").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("$2").doesNotContain("hash");
    }

    @Test
    @DisplayName("change on a Telegram-only account: 409 with a plain reason, nothing changed")
    void telegramOnly_changeRefused() throws Exception {
        User u = user(ExternalIdentityResolver.NO_PASSWORD);

        String body = mockMvc.perform(post("/api/v1/account/password").with(as(u)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"anything\",\"newPassword\":\"new-password-1\"}"))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("нет пароля").doesNotContain("неверно");
        assertThat(userRepository.findById(u.getId()).orElseThrow().getPasswordHash()).isEqualTo(ExternalIdentityResolver.NO_PASSWORD);
    }

    @Test
    @DisplayName("anonymous: 401")
    void anonymous() throws Exception {
        mockMvc.perform(get("/api/v1/account/password")).andExpect(status().isUnauthorized());
    }
}
