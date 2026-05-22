package com.example.autoauction.diagnostic.web;

import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.web.dto.DiagnosticRequestResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DiagnosticWorkController.class)
@DisplayName("Тесты контроллера работы диагноста")
class DiagnosticWorkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DiagnosticRequestService requestService;

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
    @WithMockUser(roles = {"DIAGNOSTIC"})
    @DisplayName("Получение доступных заявок - успех")
    void getPendingRequests_Success() throws Exception {
        when(requestService.getPendingRequests()).thenReturn(List.of(response));

        mockMvc.perform(get("/api/diagnostic/work/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(100))
                .andExpect(jsonPath("$[0].brand").value("BMW"));
    }

    @Test
    @WithMockUser(roles = {"USER"}) // Не диагност
    @DisplayName("Доступ к эндпоинту без роли DIAGNOSTIC - запрещён")
    void getPendingRequests_NoRole_ReturnsForbidden() throws Exception {
        mockMvc.perform(get("/api/diagnostic/work/pending"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"DIAGNOSTIC"})
    @DisplayName("Назначение заявки - успех")
    void assignRequest_Success() throws Exception {
        when(requestService.assignToDiagnostic(eq(100L), anyLong())).thenReturn(response);

        mockMvc.perform(post("/api/diagnostic/work/100/assign"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100));
    }

    @Test
    @WithMockUser(roles = {"DIAGNOSTIC"})
    @DisplayName("Назначение уже занятой заявки - конфликт")
    void assignRequest_AlreadyAssigned_ReturnsConflict() throws Exception {
        when(requestService.assignToDiagnostic(eq(100L), anyLong()))
                .thenThrow(new IllegalStateException("Не удалось назначить заявку"));

        mockMvc.perform(post("/api/diagnostic/work/100/assign"))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = {"DIAGNOSTIC"})
    @DisplayName("Получение заявки с шаблоном - успех")
    void getRequestWithTemplate_Success() throws Exception {
        when(requestService.getRequestWithTemplate(eq(100L), anyLong())).thenReturn(response);

        mockMvc.perform(get("/api/diagnostic/work/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100));
    }

    @Test
    @WithMockUser(roles = {"DIAGNOSTIC"})
    @DisplayName("Доступ к чужой заявке - запрещён")
    void getRequestWithTemplate_WrongDiagnostic_ReturnsForbidden() throws Exception {
        when(requestService.getRequestWithTemplate(eq(100L), anyLong()))
                .thenThrow(new SecurityException("Вы не назначены на эту заявку"));

        mockMvc.perform(get("/api/diagnostic/work/100"))
                .andExpect(status().isForbidden());
    }
}