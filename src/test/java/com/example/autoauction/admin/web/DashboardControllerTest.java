package com.example.autoauction.admin.web;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auth.infrastructure.security.JwtService;
import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.web.dto.DiagnosticRequestResponse;
import com.example.autoauction.user.application.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DashboardController.class)
@Import(TestSecurityConfig.class)
@DisplayName("Тесты DashboardController")
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuctionRepository auctionRepository;

    @MockBean
    private UserService userService;

    @MockBean
    private DiagnosticRequestService diagnosticRequestService;

    @MockBean
    private JwtService jwtService;

    @Nested
    @DisplayName("GET /api/v1/admin/dashboard")
    class DashboardTests {

        @Test
        @WithMockCustomUser(roles = {"ADMIN"})
        @DisplayName("Возвращает статистику дашборда")
        void getDashboard_ReturnsStats() throws Exception {
            when(auctionRepository.findAll()).thenReturn(List.of());
            when(auctionRepository.findByStatus(AuctionStatus.ACTIVE)).thenReturn(List.of());
            when(auctionRepository.findByStatus(AuctionStatus.CREATED)).thenReturn(List.of());
            when(userService.countUsers()).thenReturn(10L);
            when(diagnosticRequestService.getCompletedReports(any(Pageable.class)))
                    .thenReturn(Page.empty());
            when(diagnosticRequestService.getPendingRequests()).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/admin/dashboard"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalAuctions").value(0))
                    .andExpect(jsonPath("$.activeAuctions").value(0))
                    .andExpect(jsonPath("$.totalUsers").value(10))
                    .andExpect(jsonPath("$.pendingReports").value(0))
                    .andExpect(jsonPath("$.pendingRequests").value(0));
        }

        @Test
        @WithMockCustomUser(roles = {"ADMIN"})
        @DisplayName("Возвращает корректные числа при наличии данных")
        void getDashboard_ReturnsCorrectCounts() throws Exception {
            Auction active1 = mock(Auction.class);
            Auction active2 = mock(Auction.class);
            when(auctionRepository.findAll()).thenReturn(List.of(active1, active2));
            when(auctionRepository.findByStatus(AuctionStatus.ACTIVE)).thenReturn(List.of(active1, active2));
            when(auctionRepository.findByStatus(AuctionStatus.CREATED)).thenReturn(List.of());
            when(userService.countUsers()).thenReturn(25L);
            Page<DiagnosticRequestResponse> reports = new PageImpl<>(List.of(new DiagnosticRequestResponse()));
            when(diagnosticRequestService.getCompletedReports(any(Pageable.class))).thenReturn(reports);
            when(diagnosticRequestService.getPendingRequests()).thenReturn(List.of(new DiagnosticRequestResponse()));

            mockMvc.perform(get("/api/v1/admin/dashboard"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalAuctions").value(2))
                    .andExpect(jsonPath("$.activeAuctions").value(2))
                    .andExpect(jsonPath("$.totalUsers").value(25))
                    .andExpect(jsonPath("$.pendingReports").value(1))
                    .andExpect(jsonPath("$.pendingRequests").value(1));
        }
    }
}
