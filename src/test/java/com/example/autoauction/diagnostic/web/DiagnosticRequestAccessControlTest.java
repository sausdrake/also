package com.example.autoauction.diagnostic.web;

import com.example.autoauction.auth.infrastructure.security.JwtService;
import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.config.TestSecurityConfig;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.web.dto.DiagnosticRequestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Тесты контроля доступа к заявкам диагностики:
 * — владелец видит свою заявку
 * — чужой пользователь получает 403
 * — ADMIN видит любую заявку
 * — DIAGNOSTIC видит только заявку, назначенную на него
 */
@WebMvcTest(DiagnosticRequestController.class)
@Import(TestSecurityConfig.class)
@DisplayName("DiagnosticRequestController — контроль доступа")
class DiagnosticRequestAccessControlTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DiagnosticRequestService requestService;

    @MockBean
    private JwtService jwtService;

    private static final Long OWNER_ID        = 10L;
    private static final Long STRANGER_ID     = 20L;
    private static final Long DIAGNOSTICIAN_ID = 30L;
    private static final Long REQUEST_ID      = 100L;

    private DiagnosticRequestResponse ownerResponse;
    private DiagnosticRequestResponse assignedResponse;

    @BeforeEach
    void setUp() {
        // Заявка принадлежит OWNER, диагностик НЕ назначен
        ownerResponse = new DiagnosticRequestResponse();
        ownerResponse.setId(REQUEST_ID);
        ownerResponse.setUserId(OWNER_ID);
        ownerResponse.setAssignedTo(null);
        ownerResponse.setBrand("Toyota");
        ownerResponse.setStatus("PENDING");

        // Заявка принадлежит OWNER, диагностик назначен
        assignedResponse = new DiagnosticRequestResponse();
        assignedResponse.setId(REQUEST_ID);
        assignedResponse.setUserId(OWNER_ID);
        assignedResponse.setAssignedTo(DIAGNOSTICIAN_ID);
        assignedResponse.setBrand("Toyota");
        assignedResponse.setStatus("IN_PROGRESS");
    }

    @Nested
    @DisplayName("GET /api/v1/diagnostic/requests/{id} — доступ к конкретной заявке")
    class GetRequestAccessTests {

        @Test
        @WithMockCustomUser(userId = 10, username = "owner", roles = {"USER"})
        @DisplayName("Владелец заявки получает 200")
        void owner_Gets200() throws Exception {
            when(requestService.getRequestDetails(REQUEST_ID)).thenReturn(ownerResponse);

            mockMvc.perform(get("/api/v1/diagnostic/requests/{id}", REQUEST_ID))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockCustomUser(userId = 20, username = "stranger", roles = {"USER"})
        @DisplayName("Чужой пользователь получает 403")
        void stranger_Gets403() throws Exception {
            when(requestService.getRequestDetails(REQUEST_ID)).thenReturn(ownerResponse);

            mockMvc.perform(get("/api/v1/diagnostic/requests/{id}", REQUEST_ID))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockCustomUser(userId = 99, username = "admin", roles = {"ADMIN"})
        @DisplayName("ADMIN видит любую заявку — 200")
        void admin_Gets200() throws Exception {
            when(requestService.getRequestDetails(REQUEST_ID)).thenReturn(ownerResponse);

            mockMvc.perform(get("/api/v1/diagnostic/requests/{id}", REQUEST_ID))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockCustomUser(userId = 30, username = "diag", roles = {"DIAGNOSTIC"})
        @DisplayName("DIAGNOSTIC назначенный на заявку — 200")
        void assignedDiagnostician_Gets200() throws Exception {
            when(requestService.getRequestDetails(REQUEST_ID)).thenReturn(assignedResponse);

            mockMvc.perform(get("/api/v1/diagnostic/requests/{id}", REQUEST_ID))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockCustomUser(userId = 31, username = "other_diag", roles = {"DIAGNOSTIC"})
        @DisplayName("DIAGNOSTIC НЕ назначенный — 403")
        void unassignedDiagnostician_Gets403() throws Exception {
            when(requestService.getRequestDetails(REQUEST_ID)).thenReturn(assignedResponse);

            mockMvc.perform(get("/api/v1/diagnostic/requests/{id}", REQUEST_ID))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Неаутентифицированный пользователь (currentUser=null) — 401")
        void unauthenticated_Gets401() throws Exception {
            // TestSecurityConfig допускает запрос, но currentUser будет null —
            // контроллер явно возвращает 401
            when(requestService.getRequestDetails(REQUEST_ID)).thenReturn(ownerResponse);

            mockMvc.perform(get("/api/v1/diagnostic/requests/{id}", REQUEST_ID))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/diagnostic/requests/my — только свои заявки")
    class GetMyRequestsTests {

        @Test
        @WithMockCustomUser(userId = 10, username = "owner", roles = {"USER"})
        @DisplayName("Авторизованный пользователь получает только свои заявки — 200")
        void authenticated_Gets200() throws Exception {
            when(requestService.getUserRequests(eq(OWNER_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(ownerResponse)));

            mockMvc.perform(get("/api/v1/diagnostic/requests/my"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Неаутентифицированный (currentUser=null) — 401")
        void unauthenticated_Gets401() throws Exception {
            mockMvc.perform(get("/api/v1/diagnostic/requests/my"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
