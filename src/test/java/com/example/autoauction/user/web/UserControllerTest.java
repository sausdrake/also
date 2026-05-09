// user/web/UserControllerTest.java
package com.example.autoauction.user.web;

import com.example.autoauction.admin.web.TestSecurityConfig;
import com.example.autoauction.auth.infrastructure.security.JwtService;
import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.user.application.UserService;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Slf4j
@WebMvcTest(UserController.class)
@Import(TestSecurityConfig.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private com.example.autoauction.user.domain.port.RoleRepository roleRepository;

    @MockBean
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private User testUser;
    private UserController.CreateUserRequest createRequest;

    @BeforeEach
    void setUp() {
        Role role = new Role(1L, "ROLE_USER", "Обычный пользователь");

        testUser = new User(
                1L,
                "testuser",
                "test@example.com",
                "encodedPassword",
                true,
                Set.of(role)
        );

        createRequest = new UserController.CreateUserRequest(
                "newuser",
                "new@example.com",
                "password123"
        );

        when(passwordEncoder.encode(any(String.class))).thenReturn("encodedPassword");
    }

    @Test
    @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
    void getUsers_ShouldReturnList() throws Exception {
        when(userService.listUsers()).thenReturn(Arrays.asList(testUser));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("testuser"));
    }

    @Test
    @WithMockCustomUser(userId = 1L, username = "testuser", roles = {"USER"})
    void getUser_ExistingId_ShouldReturnUser() throws Exception {
        when(userService.getUser(1L)).thenReturn(Optional.of(testUser));

        mockMvc.perform(get("/api/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("testuser"));
    }

    @Test
    @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
    void getUser_NonExistingId_ShouldReturnNotFound() throws Exception {
        when(userService.getUser(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/users/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
    void createUser_ValidRequest_ShouldReturnCreated() throws Exception {
        when(roleRepository.findByName("ROLE_USER")).thenReturn(Optional.of(new Role(1L, "ROLE_USER", "Обычный пользователь")));
        when(userService.createUser(any(User.class))).thenReturn(testUser);

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("testuser"));
    }
}