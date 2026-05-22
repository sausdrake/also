package com.example.autoauction.diagnostic.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Тесты безопасности доступа к эндпоинтам диагностики")
class SecurityAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Доступ к эндпоинтам диагноста без авторизации - 401")
    void diagnosticEndpoints_WithoutAuth_ReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/diagnostic/work/pending"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/diagnostic/work/my"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"USER"})
    @DisplayName("Доступ к эндпоинтам диагноста с ролью USER - 403")
    void diagnosticEndpoints_WithUserRole_ReturnsForbidden() throws Exception {
        mockMvc.perform(get("/api/diagnostic/work/pending"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("Доступ к эндпоинтам диагноста с ролью ADMIN - разрешён")
    void diagnosticEndpoints_WithAdminRole_ReturnsOk() throws Exception {
        mockMvc.perform(get("/api/diagnostic/work/pending"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"DIAGNOSTIC"})
    @DisplayName("Доступ к эндпоинтам диагноста с ролью DIAGNOSTIC - разрешён")
    void diagnosticEndpoints_WithDiagnosticRole_ReturnsOk() throws Exception {
        mockMvc.perform(get("/api/diagnostic/work/pending"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"USER"})
    @DisplayName("Создание заявки на диагностику с ролью USER - разрешено")
    void createDiagnosticRequest_WithUserRole_ReturnsCreated() throws Exception {
        String requestJson = """
                {
                    "brand": "BMW",
                    "model": "X5",
                    "year": 2022,
                    "vin": "WBSDE91070CZ12345"
                }
                """;

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/diagnostic-requests")
                        .contentType("application/json")
                        .content(requestJson))
                .andExpect(status().isCreated());
    }
}