// admin/web/AdminAuctionControllerTest.java
package com.example.autoauction.admin.web;

import com.example.autoauction.admin.application.AdminAuctionService;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.web.dto.AuctionCreateRequest;
import com.example.autoauction.auction.web.dto.AuctionResponse;
import com.example.autoauction.auth.infrastructure.security.JwtService;
import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.user.domain.port.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminAuctionController.class)
@Import(TestSecurityConfig.class)
@DisplayName("Тесты контроллера администрирования аукционов")
class AdminAuctionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminAuctionService adminAuctionService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private AuctionCreateRequest validCreateRequest;
    private AuctionResponse auctionResponse;
    private AuctionResponse activeAuctionResponse;
    private AuctionResponse cancelledAuctionResponse;
    private AuctionResponse completedAuctionResponse;
    private final Long adminId = 1L;

    @BeforeEach
    void setUp() {
        objectMapper.registerModule(new JavaTimeModule());

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime startTime = now.plusDays(1);
        OffsetDateTime endTime = startTime.plusDays(7);

        validCreateRequest = new AuctionCreateRequest(
                1L,
                new BigDecimal("1000000"),
                new BigDecimal("1200000"),
                new BigDecimal("1500000"),
                new BigDecimal("10000"),
                startTime,
                endTime
        );

        auctionResponse = new AuctionResponse(
                1L, 1L, "BMW M5 2023",
                new BigDecimal("1000000"), new BigDecimal("1000000"),
                new BigDecimal("1200000"), new BigDecimal("1500000"),
                new BigDecimal("10000"), startTime, endTime,
                AuctionStatus.CREATED, 0, null, null, adminId, now,
                false, null, null, null, 0
        );

        activeAuctionResponse = new AuctionResponse(
                1L, 1L, "BMW M5 2023",
                new BigDecimal("1000000"), new BigDecimal("1050000"),
                new BigDecimal("1200000"), new BigDecimal("1500000"),
                new BigDecimal("10000"), startTime, endTime,
                AuctionStatus.ACTIVE, 2, 5L, new BigDecimal("1050000"), adminId, now,
                false, null, null, null, 0
        );

        cancelledAuctionResponse = new AuctionResponse(
                1L, 1L, "BMW M5 2023",
                new BigDecimal("1000000"), new BigDecimal("1000000"),
                new BigDecimal("1200000"), new BigDecimal("1500000"),
                new BigDecimal("10000"), startTime, endTime,
                AuctionStatus.CANCELLED, 0, null, null, adminId, now,
                false, null, null, null, 0
        );

        completedAuctionResponse = new AuctionResponse(
                1L, 1L, "BMW M5 2023",
                new BigDecimal("1000000"), new BigDecimal("1200000"),
                new BigDecimal("1200000"), new BigDecimal("1500000"),
                new BigDecimal("10000"), startTime, endTime,
                AuctionStatus.COMPLETED, 5, 3L, new BigDecimal("1200000"), adminId, now,
                false, null, null, null, 0
        );
    }

    // ==================== CREATE AUCTION TESTS ====================

    @Nested
    @DisplayName("POST /api/admin/auctions - Создание аукциона")
    class CreateAuctionTests {

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Успешное создание аукциона с валидными данными")
        void createAuction_WithValidData_ShouldReturnCreated() throws Exception {
            when(adminAuctionService.createAuction(any(AuctionCreateRequest.class), eq(adminId)))
                    .thenReturn(auctionResponse);

            mockMvc.perform(post("/api/admin/auctions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validCreateRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.status").value("CREATED"));

            verify(adminAuctionService, times(1)).createAuction(any(), eq(adminId));
        }

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Создание аукциона с невалидными данными - должно вернуть 400")
        void createAuction_WithInvalidData_ShouldReturnBadRequest() throws Exception {
            AuctionCreateRequest invalidRequest = new AuctionCreateRequest(
                    null, new BigDecimal("-1000"), null, null,
                    new BigDecimal("0"), null, null
            );

            mockMvc.perform(post("/api/admin/auctions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest());

            verify(adminAuctionService, never()).createAuction(any(), any());
        }

        @Test
        @WithMockCustomUser(userId = 2L, username = "user", roles = {"USER"})
        @DisplayName("Создание аукциона пользователем без прав ADMIN - должно вернуть 403")
        void createAuction_ByNonAdmin_ShouldReturnForbidden() throws Exception {
            mockMvc.perform(post("/api/admin/auctions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validCreateRequest)))
                    .andExpect(status().isForbidden());

            verify(adminAuctionService, never()).createAuction(any(), any());
        }
    }

    // ==================== GET ALL AUCTIONS TESTS ====================

    @Nested
    @DisplayName("GET /api/admin/auctions - Получение всех аукционов с пагинацией")
    class GetAllAuctionsTests {

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Успешное получение списка аукционов с пагинацией")
        void getAllAuctions_ShouldReturnPage() throws Exception {
            Pageable pageable = PageRequest.of(0, 20);
            List<AuctionResponse> auctions = List.of(auctionResponse, activeAuctionResponse);
            Page<AuctionResponse> page = new PageImpl<>(auctions, pageable, auctions.size());

            when(adminAuctionService.getAllAuctions(any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/admin/auctions")
                            .param("page", "0")
                            .param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.pageable.pageNumber").value(0))
                    .andExpect(jsonPath("$.pageable.pageSize").value(20));

            verify(adminAuctionService, times(1)).getAllAuctions(any(Pageable.class));
        }

        @Test
        @WithMockCustomUser(userId = 2L, username = "user", roles = {"USER"})
        @DisplayName("Получение списка аукционов пользователем без прав ADMIN - должно вернуть 403")
        void getAllAuctions_ByNonAdmin_ShouldReturnForbidden() throws Exception {
            mockMvc.perform(get("/api/admin/auctions"))
                    .andExpect(status().isForbidden());

            verify(adminAuctionService, never()).getAllAuctions(any());
        }
    }

    // ==================== GET AUCTION BY ID TESTS ====================

    @Nested
    @DisplayName("GET /api/admin/auctions/{id} - Получение аукциона по ID")
    class GetAuctionByIdTests {

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Успешное получение существующего аукциона")
        void getAuction_ExistingId_ShouldReturnAuction() throws Exception {
            when(adminAuctionService.getAuction(1L)).thenReturn(auctionResponse);

            mockMvc.perform(get("/api/admin/auctions/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1));

            verify(adminAuctionService, times(1)).getAuction(1L);
        }

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Получение несуществующего аукциона - должно вернуть 404")
        void getAuction_NonExistingId_ShouldReturnNotFound() throws Exception {
            when(adminAuctionService.getAuction(999L))
                    .thenThrow(new IllegalArgumentException("Аукцион с ID 999 не найден"));

            mockMvc.perform(get("/api/admin/auctions/999"))
                    .andExpect(status().isNotFound());

            verify(adminAuctionService, times(1)).getAuction(999L);
        }
    }

    // ==================== START AUCTION TESTS ====================

    @Nested
    @DisplayName("POST /api/admin/auctions/{id}/start - Запуск аукциона")
    class StartAuctionTests {

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Успешный запуск созданного аукциона")
        void startAuction_WhenCreated_ShouldReturnOk() throws Exception {
            when(adminAuctionService.startAuction(1L, adminId)).thenReturn(activeAuctionResponse);

            mockMvc.perform(post("/api/admin/auctions/1/start"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));

            verify(adminAuctionService, times(1)).startAuction(1L, adminId);
        }

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Запуск уже активного аукциона - должно вернуть 409")
        void startAuction_WhenAlreadyActive_ShouldReturnConflict() throws Exception {
            when(adminAuctionService.startAuction(1L, adminId))
                    .thenThrow(new IllegalStateException("Аукцион уже запущен"));

            mockMvc.perform(post("/api/admin/auctions/1/start"))
                    .andExpect(status().isConflict());

            verify(adminAuctionService, times(1)).startAuction(1L, adminId);
        }
    }

    // ==================== CANCEL AUCTION TESTS ====================

    @Nested
    @DisplayName("POST /api/admin/auctions/{id}/cancel - Отмена аукциона")
    class CancelAuctionTests {

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Успешная отмена созданного аукциона")
        void cancelAuction_WhenCreated_ShouldReturnOk() throws Exception {
            when(adminAuctionService.cancelAuction(1L, adminId)).thenReturn(cancelledAuctionResponse);

            mockMvc.perform(post("/api/admin/auctions/1/cancel"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));

            verify(adminAuctionService, times(1)).cancelAuction(1L, adminId);
        }

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Отмена уже завершенного аукциона - должно вернуть 409")
        void cancelAuction_WhenCompleted_ShouldReturnConflict() throws Exception {
            when(adminAuctionService.cancelAuction(1L, adminId))
                    .thenThrow(new IllegalStateException("Невозможно отменить аукцион в статусе COMPLETED"));

            mockMvc.perform(post("/api/admin/auctions/1/cancel"))
                    .andExpect(status().isConflict());

            verify(adminAuctionService, times(1)).cancelAuction(1L, adminId);
        }
    }

    // ==================== COMPLETE AUCTION TESTS ====================

    @Nested
    @DisplayName("POST /api/admin/auctions/{id}/complete - Принудительное завершение аукциона")
    class CompleteAuctionTests {

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Успешное принудительное завершение аукциона")
        void completeAuction_ShouldReturnOk() throws Exception {
            when(adminAuctionService.completeAuction(1L, adminId)).thenReturn(completedAuctionResponse);

            mockMvc.perform(post("/api/admin/auctions/1/complete"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"));

            verify(adminAuctionService, times(1)).completeAuction(1L, adminId);
        }

        @Test
        @WithMockCustomUser(userId = 2L, username = "user", roles = {"USER"})
        @DisplayName("Принудительное завершение аукциона пользователем без прав ADMIN - должно вернуть 403")
        void completeAuction_ByNonAdmin_ShouldReturnForbidden() throws Exception {
            mockMvc.perform(post("/api/admin/auctions/1/complete"))
                    .andExpect(status().isForbidden());

            verify(adminAuctionService, never()).completeAuction(any(), any());
        }
    }

    // ==================== GET AUCTIONS BY STATUS TESTS ====================

    @Nested
    @DisplayName("GET /api/admin/auctions/by-status - Получение аукционов по статусу с пагинацией")
    class GetAuctionsByStatusTests {

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Успешное получение аукционов по статусу")
        void getAuctionsByStatus_ValidStatus_ShouldReturnPage() throws Exception {
            Pageable pageable = PageRequest.of(0, 20);
            List<AuctionResponse> auctions = List.of(auctionResponse);
            Page<AuctionResponse> page = new PageImpl<>(auctions, pageable, auctions.size());

            when(adminAuctionService.getAuctionsByStatus(eq("CREATED"), any(Pageable.class)))
                    .thenReturn(page);

            mockMvc.perform(get("/api/admin/auctions/by-status")
                            .param("status", "CREATED")
                            .param("page", "0")
                            .param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content.length()").value(1));

            verify(adminAuctionService, times(1)).getAuctionsByStatus(eq("CREATED"), any(Pageable.class));
        }

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Неверный статус - должно вернуть 400")
        void getAuctionsByStatus_InvalidStatus_ShouldReturnBadRequest() throws Exception {
            when(adminAuctionService.getAuctionsByStatus(eq("INVALID_STATUS"), any(Pageable.class)))
                    .thenThrow(new IllegalArgumentException("Неверный статус аукциона: INVALID_STATUS"));

            mockMvc.perform(get("/api/admin/auctions/by-status")
                            .param("status", "INVALID_STATUS")
                            .param("page", "0")
                            .param("size", "20"))
                    .andExpect(status().isBadRequest());

            verify(adminAuctionService, times(1)).getAuctionsByStatus(eq("INVALID_STATUS"), any(Pageable.class));
        }
    }

    // ==================== GET AUCTIONS BY VEHICLE ID TESTS ====================

    @Nested
    @DisplayName("GET /api/admin/auctions/vehicle/{vehicleId} - Получение аукционов по ID автомобиля")
    class GetAuctionsByVehicleIdTests {

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Успешное получение аукционов по ID автомобиля")
        void getAuctionsByVehicleId_ShouldReturnList() throws Exception {
            List<AuctionResponse> auctions = List.of(auctionResponse);
            when(adminAuctionService.getAuctionsByVehicleId(1L)).thenReturn(auctions);

            mockMvc.perform(get("/api/admin/auctions/vehicle/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));

            verify(adminAuctionService, times(1)).getAuctionsByVehicleId(1L);
        }
    }
}