package com.example.autoauction.admin.web;

import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice 1A: full HTTP-layer proof of the trust/untrust endpoints' ADMIN-only enforcement against
 * the REAL security chain - {@code @SpringBootTest} + {@code @AutoConfigureMockMvc} (H2, "test"
 * profile), mirroring {@code AdminDepositControllerTest}'s own established convention exactly.
 *
 * <p>Deliberately NOT a {@code @WebMvcTest} slice test: {@code AdminUserControllerTest}'s own
 * {@code TestSecurityConfig} (package {@code admin.web}) matches the URL pattern
 * {@code /api/admin/**} rather than the real {@code /api/v1/admin/**} - a pre-existing,
 * unrelated gap (predates this slice, already affects every other endpoint in that controller)
 * that makes role-rejection unverifiable in that harness. This class exists specifically to
 * prove enforcement against the REAL {@code SecurityConfig}, not to work around that gap.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Slice 1A: /admin/users/{id}/trust и /untrust — ADMIN-only enforcement (real security chain)")
class AdminUserTrustApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private Long targetUserId;

    @BeforeEach
    void setUp() {
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new Role(null, "ROLE_USER", "Обычный пользователь")));
        User target = new User(null, "trusttarget" + System.nanoTime(),
                "trusttarget" + System.nanoTime() + "@test.com", "encoded", true, Set.of(userRole));
        targetUserId = userRepository.save(target).getId();
    }

    @Test
    @DisplayName("ADMIN может доверить пользователю участие в торгах")
    @WithMockCustomUser(userId = 42L, username = "admin", roles = {"ADMIN"})
    void trust_Admin_Succeeds() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/users/" + targetUserId + "/trust"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trusted").value(true));

        User saved = userRepository.findById(targetUserId).orElseThrow();
        assertThat(saved.isTrusted()).isTrue();
        assertThat(saved.getVerifiedByAdminId()).isEqualTo(42L);
        assertThat(saved.getVerifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("ADMIN может отозвать доверие пользователю")
    @WithMockCustomUser(userId = 42L, username = "admin", roles = {"ADMIN"})
    void untrust_Admin_Succeeds() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/users/" + targetUserId + "/trust")).andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/users/" + targetUserId + "/untrust"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trusted").value(false));

        User saved = userRepository.findById(targetUserId).orElseThrow();
        assertThat(saved.isTrusted()).isFalse();
    }

    @Test
    @DisplayName("Неаутентифицированный запрос к /trust отклоняется (401)")
    void trust_Anonymous_Rejected() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/users/" + targetUserId + "/trust"))
                .andExpect(status().isUnauthorized());

        assertThat(userRepository.findById(targetUserId).orElseThrow().isTrusted()).isFalse();
    }

    @Test
    @DisplayName("Запрос роли USER к /trust отклоняется (403)")
    @WithMockCustomUser(userId = 2L, username = "user", roles = {"USER"})
    void trust_UserRole_Rejected() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/users/" + targetUserId + "/trust"))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(targetUserId).orElseThrow().isTrusted()).isFalse();
    }

    @Test
    @DisplayName("Запрос роли DIAGNOSTIC к /trust отклоняется (403)")
    @WithMockCustomUser(userId = 3L, username = "diag", roles = {"DIAGNOSTIC"})
    void trust_DiagnosticRole_Rejected() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/users/" + targetUserId + "/trust"))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(targetUserId).orElseThrow().isTrusted()).isFalse();
    }

    @Test
    @DisplayName("Неаутентифицированный запрос к /untrust отклоняется (401)")
    void untrust_Anonymous_Rejected() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/users/" + targetUserId + "/untrust"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Запрос роли USER к /untrust отклоняется (403)")
    @WithMockCustomUser(userId = 2L, username = "user", roles = {"USER"})
    void untrust_UserRole_Rejected() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/users/" + targetUserId + "/untrust"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Клиент не может подменить acting admin id - тело запроса не содержит такого поля на уровне контракта")
    @WithMockCustomUser(userId = 99L, username = "admin2", roles = {"ADMIN"})
    void trust_ActorAlwaysFromAuthenticatedContext() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/users/" + targetUserId + "/trust"))
                .andExpect(status().isOk());

        User saved = userRepository.findById(targetUserId).orElseThrow();
        // PATCH /trust accepts no request body at all (no @RequestBody on the endpoint) - there
        // is no field for a client to even attempt to supply an admin id through.
        assertThat(saved.getVerifiedByAdminId()).isEqualTo(99L);
    }
}
