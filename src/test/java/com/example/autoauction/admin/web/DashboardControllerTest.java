package com.example.autoauction.admin.web;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auth.infrastructure.security.JwtService;
import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.web.dto.DiagnosticRequestResponse;
import com.example.autoauction.user.application.UserService;
import com.example.autoauction.vehicle.application.VehicleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
    private VehicleService vehicleService;

    @MockBean
    private JwtService jwtService;

    @Nested
    @DisplayName("GET /api/v1/admin/dashboard")
    class DashboardTests {

        @Test
        @WithMockCustomUser(roles = {"ADMIN"})
        @DisplayName("Возвращает статистику дашборда")
        void getDashboard_ReturnsStats() throws Exception {
            when(vehicleService.countAllVehicles()).thenReturn(0L);
            when(userService.countUsers()).thenReturn(10L);
            when(auctionRepository.findByStatus(any())).thenReturn(List.of());
            when(diagnosticRequestService.getCompletedReports(any(Pageable.class)))
                    .thenReturn(Page.empty());
            when(diagnosticRequestService.countApprovedReports()).thenReturn(0L);

            // the six fields the admin dashboard reads (client AdminDashboard.tsx), and only those
            mockMvc.perform(get("/api/v1/admin/dashboard"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(6))
                    .andExpect(jsonPath("$.totalVehicles").value(0))
                    .andExpect(jsonPath("$.totalUsers").value(10))
                    .andExpect(jsonPath("$.activeAuctions").value(0))
                    .andExpect(jsonPath("$.finishedAuctions").value(0))
                    .andExpect(jsonPath("$.pendingReports").value(0))
                    .andExpect(jsonPath("$.approvedReports").value(0));
        }

        @Test
        @WithMockCustomUser(roles = {"ADMIN"})
        @DisplayName("Возвращает корректные числа при наличии данных; WAITING_FOR_PAYMENT не считается завершённым")
        @SuppressWarnings("deprecation") // legacy COMPLETED still counts as finished
        void getDashboard_ReturnsCorrectCounts() throws Exception {
            Auction a = mock(Auction.class);
            when(vehicleService.countAllVehicles()).thenReturn(7L);
            when(userService.countUsers()).thenReturn(25L);
            when(auctionRepository.findByStatus(any())).thenReturn(List.of());
            when(auctionRepository.findByStatus(AuctionStatus.ACTIVE)).thenReturn(List.of(a, a));
            when(auctionRepository.findByStatus(AuctionStatus.COMPLETED)).thenReturn(List.of(a));
            when(auctionRepository.findByStatus(AuctionStatus.SOLD)).thenReturn(List.of(a, a));
            when(auctionRepository.findByStatus(AuctionStatus.SALE_FAILED)).thenReturn(List.of(a));
            when(auctionRepository.findByStatus(AuctionStatus.EXPIRED)).thenReturn(List.of(a));
            when(auctionRepository.findByStatus(AuctionStatus.CANCELLED)).thenReturn(List.of(a));
            // in progress, not finished
            when(auctionRepository.findByStatus(AuctionStatus.WAITING_FOR_PAYMENT)).thenReturn(List.of(a, a, a));
            Page<DiagnosticRequestResponse> reports =
                    new PageImpl<>(List.of(new DiagnosticRequestResponse()), PageRequest.of(0, 1), 4);
            when(diagnosticRequestService.getCompletedReports(any(Pageable.class))).thenReturn(reports);
            when(diagnosticRequestService.countApprovedReports()).thenReturn(3L);

            mockMvc.perform(get("/api/v1/admin/dashboard"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalVehicles").value(7))
                    .andExpect(jsonPath("$.totalUsers").value(25))
                    .andExpect(jsonPath("$.activeAuctions").value(2))
                    .andExpect(jsonPath("$.finishedAuctions").value(6))
                    .andExpect(jsonPath("$.pendingReports").value(4))
                    .andExpect(jsonPath("$.approvedReports").value(3));
        }
    }
}
