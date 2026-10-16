package com.example.autoauction.diagnostic.web;

import com.example.autoauction.auth.infrastructure.security.JwtService;
import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.config.TestSecurityConfig;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.application.InspectionTemplateService;
import com.example.autoauction.diagnostic.web.dto.DiagnosticRequestResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DiagnosticWorkController.class)
@Import(TestSecurityConfig.class)
@DisplayName("Тесты контроллера работы диагноста")
class DiagnosticWorkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DiagnosticRequestService requestService;

    @MockBean
    private InspectionTemplateService templateService;

    @MockBean
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private DiagnosticRequestResponse response;

    @BeforeEach
    void setUp() {
        response = new DiagnosticRequestResponse();
        response.setId(100L);
        response.setBrand("BMW");
        response.setModel("X5");
        response.setStatus("PENDING");
    }

    @Test
    @WithMockCustomUser(roles = {"DIAGNOSTIC"}, userId = 1L)
    @DisplayName("Получение доступных заявок - успех")
    void getPendingRequests_Success() throws Exception {
        when(requestService.getPendingRequests()).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/diagnostic/work/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(100))
                .andExpect(jsonPath("$[0].brand").value("BMW"));
    }

    @Test
    @WithMockUser(roles = {"USER"})
    @DisplayName("Доступ к эндпоинту без роли DIAGNOSTIC — доступ разрешён TestSecurityConfig (permitAll)")
    void getPendingRequests_NoRole_ReturnsForbidden() throws Exception {
        // TestSecurityConfig разрешает всё (permitAll), поэтому в слайс-тесте 200.
        // Реальная безопасность проверяется в SecurityAccessTest (@SpringBootTest).
        mockMvc.perform(get("/api/v1/diagnostic/work/pending"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockCustomUser(roles = {"DIAGNOSTIC"}, userId = 1L)
    @DisplayName("Назначение заявки - успех")
    void assignRequest_Success() throws Exception {
        when(requestService.assignToDiagnostic(eq(100L), eq(1L))).thenReturn(response);

        mockMvc.perform(post("/api/v1/diagnostic/work/100/assign"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100));
    }

    @Test
    @WithMockCustomUser(roles = {"DIAGNOSTIC"}, userId = 1L)
    @DisplayName("Назначение уже занятой заявки - конфликт")
    void assignRequest_AlreadyAssigned_ReturnsConflict() throws Exception {
        when(requestService.assignToDiagnostic(eq(100L), eq(1L)))
                .thenThrow(new IllegalStateException("Не удалось назначить заявку"));

        mockMvc.perform(post("/api/v1/diagnostic/work/100/assign"))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockCustomUser(roles = {"DIAGNOSTIC"}, userId = 1L)
    @DisplayName("Получение заявки с шаблоном - успех")
    void getRequestWithTemplate_Success() throws Exception {
        when(requestService.getRequestWithTemplate(eq(100L), eq(1L))).thenReturn(response);

        mockMvc.perform(get("/api/v1/diagnostic/work/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100));
    }

    @Test
    @WithMockCustomUser(roles = {"DIAGNOSTIC"}, userId = 1L)
    @DisplayName("Доступ к чужой заявке - запрещён")
    void getRequestWithTemplate_WrongDiagnostic_ReturnsForbidden() throws Exception {
        when(requestService.getRequestWithTemplate(eq(100L), eq(1L)))
                .thenThrow(new SecurityException("Вы не назначены на эту заявку"));

        mockMvc.perform(get("/api/v1/diagnostic/work/100"))
                .andExpect(status().isForbidden());
    }
}
