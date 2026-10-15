package com.example.autoauction.user.web;

import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-02: POST /api/users creates accounts outside /api/auth/register (no rate limit, no
 * registration rules) - only administrators may use it. Runs against the real security chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("SEC-02: only administrators can create users through /api/users")
class UserCreationAuthorizationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    @BeforeEach
    void defaultRoleExists() {
        roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
    }

    private static RequestPostProcessor as(Long id, String role) {
        return SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(id, "u" + id, role));
    }

    private static String body(String username) {
        return "{\"username\":\"" + username + "\",\"email\":\"" + username + "@test.com\",\"password\":\"Sec02-password\"}";
    }

    @Test
    void regularUser_forbidden_noAccountCreated() throws Exception {
        String username = "sec02u" + System.nanoTime();
        mockMvc.perform(post("/api/users").with(as(501L, "USER")).contentType(MediaType.APPLICATION_JSON).content(body(username)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/users").with(as(502L, "DIAGNOSTIC")).contentType(MediaType.APPLICATION_JSON).content(body(username)))
                .andExpect(status().isForbidden());
        assertThat(userRepository.findByUsername(username)).isEmpty();
    }

    @Test
    void anonymous_unauthorized_noAccountCreated() throws Exception {
        String username = "sec02a" + System.nanoTime();
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body(username)))
                .andExpect(status().isUnauthorized());
        assertThat(userRepository.findByUsername(username)).isEmpty();
    }

    @Test
    void admin_canStillCreateUsers() throws Exception {
        String username = "sec02ad" + System.nanoTime();
        mockMvc.perform(post("/api/users").with(as(503L, "ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body(username)))
                .andExpect(status().isCreated());
        assertThat(userRepository.findByUsername(username)).isPresent();
    }
}
