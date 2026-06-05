package com.example.autoauction.admin.web;

import com.example.autoauction.auth.infrastructure.security.JwtService;
import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.user.application.UserService;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminUserController.class)
@Import(TestSecurityConfig.class)
@DisplayName("Тесты AdminUserController")
class AdminUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;

    @MockBean
    private DepositService depositService;

    @MockBean
    private JwtService jwtService;

    private User testUser;

    @BeforeEach
    void setUp() {
        Role role = new Role(1L, "ROLE_USER", "User role");
        testUser = new User(1L, "john", "john@test.com", "hash", true, Set.of(role));
    }

    @Nested
    @DisplayName("GET /api/v1/admin/users")
    class ListUsersTests {

        @Test
        @WithMockCustomUser(roles = {"ADMIN"})
        @DisplayName("Возвращает список всех пользователей")
        void listUsers_ReturnsAll() throws Exception {
            when(userService.listUsers()).thenReturn(List.of(testUser));

            mockMvc.perform(get("/api/v1/admin/users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].username").value("john"))
                    .andExpect(jsonPath("$[0].active").value(true));
        }

        @Test
        @WithMockCustomUser(roles = {"ADMIN"})
        @DisplayName("Фильтрует по роли")
        void listUsers_FilterByRole() throws Exception {
            when(userService.listUsersByRole("ROLE_USER")).thenReturn(List.of(testUser));

            mockMvc.perform(get("/api/v1/admin/users?role=ROLE_USER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].username").value("john"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/admin/users/{id}")
    class GetUserTests {

        @Test
        @WithMockCustomUser(roles = {"ADMIN"})
        @DisplayName("Возвращает пользователя по ID")
        void getUser_ReturnsUser() throws Exception {
            when(userService.getUser(1L)).thenReturn(Optional.of(testUser));

            mockMvc.perform(get("/api/v1/admin/users/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.email").value("john@test.com"));
        }

        @Test
        @WithMockCustomUser(roles = {"ADMIN"})
        @DisplayName("Возвращает 404 если не найден")
        void getUser_Returns404() throws Exception {
            when(userService.getUser(99L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/admin/users/99"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/admin/users/{id}/block")
    class BlockUserTests {

        @Test
        @WithMockCustomUser(roles = {"ADMIN"})
        @DisplayName("Блокирует пользователя")
        void blockUser_ReturnsBlocked() throws Exception {
            Role role = new Role(1L, "ROLE_USER", "User role");
            User blocked = new User(1L, "john", "john@test.com", "hash", false, Set.of(role));
            when(userService.blockUser(1L)).thenReturn(blocked);

            mockMvc.perform(patch("/api/v1/admin/users/1/block"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/admin/users/{id}/role")
    class SetRoleTests {

        @Test
        @WithMockCustomUser(roles = {"ADMIN"})
        @DisplayName("Меняет роль пользователя")
        void setRole_ReturnsUpdatedUser() throws Exception {
            Role newRole = new Role(2L, "ROLE_DIAGNOSTIC", "Diagnostic");
            User updated = new User(1L, "john", "john@test.com", "hash", true, Set.of(newRole));
            when(userService.setUserRole(eq(1L), eq("ROLE_DIAGNOSTIC"))).thenReturn(updated);

            mockMvc.perform(patch("/api/v1/admin/users/1/role")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("role", "ROLE_DIAGNOSTIC"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles[0]").value("ROLE_DIAGNOSTIC"));
        }

        @Test
        @WithMockCustomUser(roles = {"ADMIN"})
        @DisplayName("Возвращает 400 если роль не указана")
        void setRole_Returns400WhenRoleMissing() throws Exception {
            mockMvc.perform(patch("/api/v1/admin/users/1/role")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("role", ""))))
                    .andExpect(status().isBadRequest());
        }
    }
}
