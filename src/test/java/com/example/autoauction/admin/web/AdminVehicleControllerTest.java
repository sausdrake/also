package com.example.autoauction.admin.web;

import com.example.autoauction.auth.infrastructure.security.JwtService;
import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.user.domain.port.UserRepository;
import com.example.autoauction.vehicle.application.VehicleService;
import com.example.autoauction.vehicle.domain.FuelType;
import com.example.autoauction.vehicle.domain.Vehicle;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.VehicleType;
import com.example.autoauction.vehicle.web.dto.VehicleRejectRequest;
import com.example.autoauction.vehicle.web.dto.VehicleResponse;
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

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminVehicleController.class)
@Import(TestSecurityConfig.class)
@DisplayName("Тесты контроллера администрирования автомобилей")
class AdminVehicleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VehicleService vehicleService;

    // MVP-005: the controller's re-diagnostic collaborator (covered by AdminRediagnostic*Test)
    @MockBean
    private com.example.autoauction.admin.application.AdminRediagnosticService adminRediagnosticService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private VehicleResponse pendingVehicle;
    private VehicleResponse approvedVehicle;
    private VehicleResponse rejectedVehicle;
    private VehicleResponse needsFixesVehicle;
    private VehicleResponse inAuctionVehicle;
    private VehicleResponse needsRediagnosticVehicle;
    private final Long adminId = 1L;
    private final Long diagnosticId = 16L;
    private final String adminName = "admin";
    private final String diagnosticName = "diagnostic";

    @BeforeEach
    void setUp() {
        OffsetDateTime now = OffsetDateTime.now();

        // Создаём domain объекты, а потом конвертируем в Response
        Vehicle pendingDomain = new Vehicle(
                "BMW", "M5", 2023, "WBSDE91070CZ12345",
                VehicleType.SEDAN, diagnosticId, diagnosticName
        );
        pendingDomain.setId(1L);
        pendingDomain.setLicensePlate("A123BC");
        pendingDomain.setColor("black");
        pendingDomain.setMileage(10000);
        pendingDomain.setEngineCapacity(4.4);
        pendingDomain.setFuelType(FuelType.PETROL);
        pendingDomain.setTransmission("auto");
        pendingDomain.setDescription("Test car");
        pendingDomain.submitForReview(); // Статус становится PENDING_REVIEW
        pendingVehicle = VehicleResponse.fromDomain(pendingDomain);

        Vehicle approvedDomain = new Vehicle(
                "BMW", "M5", 2023, "WBSDE91070CZ12345",
                VehicleType.SEDAN, diagnosticId, diagnosticName
        );
        approvedDomain.setId(1L);
        approvedDomain.setLicensePlate("A123BC");
        approvedDomain.setColor("black");
        approvedDomain.setMileage(10000);
        approvedDomain.setEngineCapacity(4.4);
        approvedDomain.setFuelType(FuelType.PETROL);
        approvedDomain.setTransmission("auto");
        approvedDomain.setDescription("Test car");
        approvedDomain.submitForReview();
        approvedDomain.approve(adminId, adminName);
        approvedVehicle = VehicleResponse.fromDomain(approvedDomain);

        Vehicle rejectedDomain = new Vehicle(
                "BMW", "M5", 2023, "WBSDE91070CZ12345",
                VehicleType.SEDAN, diagnosticId, diagnosticName
        );
        rejectedDomain.setId(1L);
        rejectedDomain.setLicensePlate("A123BC");
        rejectedDomain.setColor("black");
        rejectedDomain.setMileage(10000);
        rejectedDomain.setEngineCapacity(4.4);
        rejectedDomain.setFuelType(FuelType.PETROL);
        rejectedDomain.setTransmission("auto");
        rejectedDomain.setDescription("Test car");
        rejectedDomain.submitForReview();
        rejectedDomain.reject(adminId, adminName, "Не хватает фотографий");
        rejectedVehicle = VehicleResponse.fromDomain(rejectedDomain);

        Vehicle needsFixesDomain = new Vehicle(
                "Audi", "RS6", 2023, "WAUZZZ4G9NN123456",
                VehicleType.WAGON, diagnosticId, diagnosticName
        );
        needsFixesDomain.setId(2L);
        needsFixesDomain.setLicensePlate("B456DE");
        needsFixesDomain.setColor("blue");
        needsFixesDomain.setMileage(5000);
        needsFixesDomain.setEngineCapacity(4.0);
        needsFixesDomain.setFuelType(FuelType.PETROL);
        needsFixesDomain.setTransmission("auto");
        needsFixesDomain.setDescription("Need fixes");
        needsFixesDomain.submitForReview();
        needsFixesDomain.reject(adminId, adminName, "Требуется дополнительная проверка");
        needsFixesVehicle = VehicleResponse.fromDomain(needsFixesDomain);

        Vehicle inAuctionDomain = new Vehicle(
                "Mercedes", "E63 AMG", 2023, "WDD2132891A123456",
                VehicleType.SEDAN, diagnosticId, diagnosticName
        );
        inAuctionDomain.setId(3L);
        inAuctionDomain.setLicensePlate("C789FG");
        inAuctionDomain.setColor("silver");
        inAuctionDomain.setMileage(15000);
        inAuctionDomain.setEngineCapacity(4.0);
        inAuctionDomain.setFuelType(FuelType.PETROL);
        inAuctionDomain.setTransmission("auto");
        inAuctionDomain.setDescription("In auction");
        inAuctionDomain.submitForReview();
        inAuctionDomain.approve(adminId, adminName);
        inAuctionDomain.markInAuction();
        inAuctionVehicle = VehicleResponse.fromDomain(inAuctionDomain);

        Vehicle needsRediagnosticDomain = new Vehicle(
                "Toyota", "Camry", 2022, "4T1BF1FK5GU123456",
                VehicleType.SEDAN, diagnosticId, diagnosticName
        );
        needsRediagnosticDomain.setId(4L);
        needsRediagnosticDomain.setLicensePlate("D012HK");
        needsRediagnosticDomain.setColor("white");
        needsRediagnosticDomain.setMileage(20000);
        needsRediagnosticDomain.setEngineCapacity(2.5);
        needsRediagnosticDomain.setFuelType(FuelType.PETROL);
        needsRediagnosticDomain.setTransmission("auto");
        needsRediagnosticDomain.setDescription("Needs rediagnostic after sale failure");
        needsRediagnosticDomain.submitForReview();
        needsRediagnosticDomain.approve(adminId, adminName);
        needsRediagnosticDomain.markInAuction();
        needsRediagnosticDomain.markNeedsRediagnostic();
        needsRediagnosticVehicle = VehicleResponse.fromDomain(needsRediagnosticDomain);
    }

    // ==================== GET PENDING VEHICLES TESTS ====================

    @Nested
    @DisplayName("GET /api/admin/vehicles/pending - Получение ожидающих проверки")
    class GetPendingVehiclesTests {

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Успешное получение списка")
        void getPendingVehicles_ShouldReturnList() throws Exception {
            when(vehicleService.getVehiclesByStatus(VehicleStatus.PENDING_REVIEW))
                    .thenReturn(List.of(pendingVehicle, needsFixesVehicle));

            mockMvc.perform(get("/api/v1/admin/vehicles/pending"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].status").value("PENDING_REVIEW"))
                    .andExpect(jsonPath("$[1].status").value("NEEDS_FIXES"));

            verify(vehicleService, times(1)).getVehiclesByStatus(VehicleStatus.PENDING_REVIEW);
        }

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Пустой список")
        void getPendingVehicles_EmptyList_ShouldReturnEmptyArray() throws Exception {
            when(vehicleService.getVehiclesByStatus(VehicleStatus.PENDING_REVIEW))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/v1/admin/vehicles/pending"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(0));

            verify(vehicleService, times(1)).getVehiclesByStatus(VehicleStatus.PENDING_REVIEW);
        }

        @Test
        @WithMockCustomUser(userId = 2L, username = "user", roles = {"USER"})
        @DisplayName("Доступ запрещён для не-админа")
        void getPendingVehicles_ByNonAdmin_ShouldReturnForbidden() throws Exception {
            mockMvc.perform(get("/api/v1/admin/vehicles/pending"))
                    .andExpect(status().isForbidden());

            verify(vehicleService, never()).getVehiclesByStatus(any());
        }
    }

    // ==================== GET NEEDS FIXES VEHICLES TESTS ====================

    @Nested
    @DisplayName("GET /api/admin/vehicles/needs-fixes - Получение требующих доработки")
    class GetNeedsFixesVehiclesTests {

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Успешное получение списка")
        void getNeedsFixesVehicles_ShouldReturnList() throws Exception {
            when(vehicleService.getVehiclesByStatus(VehicleStatus.NEEDS_FIXES))
                    .thenReturn(List.of(needsFixesVehicle));

            mockMvc.perform(get("/api/v1/admin/vehicles/needs-fixes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].status").value("NEEDS_FIXES"));

            verify(vehicleService, times(1)).getVehiclesByStatus(VehicleStatus.NEEDS_FIXES);
        }
    }

    // ==================== GET VEHICLE DETAILS TESTS ====================

    @Nested
    @DisplayName("GET /api/admin/vehicles/{id} - Получение деталей")
    class GetVehicleDetailsTests {

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Успешное получение существующего автомобиля")
        void getVehicleDetails_ExistingId_ShouldReturnVehicle() throws Exception {
            when(vehicleService.getVehicleDetails(1L)).thenReturn(pendingVehicle);

            mockMvc.perform(get("/api/v1/admin/vehicles/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.brand").value("BMW"));

            verify(vehicleService, times(1)).getVehicleDetails(1L);
        }

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Несуществующий ID - 404")
        void getVehicleDetails_NonExistingId_ShouldReturnNotFound() throws Exception {
            when(vehicleService.getVehicleDetails(999L))
                    .thenThrow(new IllegalArgumentException("Автомобиль не найден: 999"));

            mockMvc.perform(get("/api/v1/admin/vehicles/999"))
                    .andExpect(status().isNotFound());

            verify(vehicleService, times(1)).getVehicleDetails(999L);
        }
    }

    // ==================== APPROVE VEHICLE TESTS ====================

    @Nested
    @DisplayName("POST /api/admin/vehicles/{id}/approve - Одобрение")
    class ApproveVehicleTests {

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Успешное одобрение")
        void approveVehicle_ShouldReturnApprovedVehicle() throws Exception {
            when(vehicleService.approveVehicle(eq(1L), eq(adminId), eq(adminName)))
                    .thenReturn(approvedVehicle);

            mockMvc.perform(post("/api/v1/admin/vehicles/1/approve")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"));

            verify(vehicleService, times(1)).approveVehicle(eq(1L), eq(adminId), eq(adminName));
        }

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Уже одобрен - 409")
        void approveVehicle_AlreadyApproved_ShouldReturnConflict() throws Exception {
            when(vehicleService.approveVehicle(eq(1L), eq(adminId), eq(adminName)))
                    .thenThrow(new IllegalStateException("Only PENDING_REVIEW can be approved"));

            mockMvc.perform(post("/api/v1/admin/vehicles/1/approve")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isConflict());

            verify(vehicleService, times(1)).approveVehicle(eq(1L), eq(adminId), eq(adminName));
        }
    }

    // ==================== REJECT VEHICLE TESTS ====================

    @Nested
    @DisplayName("POST /api/admin/vehicles/{id}/reject - Отклонение")
    class RejectVehicleTests {

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Успешное отклонение с причиной")
        void rejectVehicle_WithReason_ShouldReturnRejectedVehicle() throws Exception {
            String reason = "Не хватает фотографий двигателя";
            VehicleRejectRequest rejectRequest = new VehicleRejectRequest(1L, adminId, adminName, reason);

            // Создаём отдельный объект для мока с нужной причиной
            VehicleResponse rejectedWithReason = VehicleResponse.fromDomain(
                    createRejectedVehicleWithReason(reason)
            );

            when(vehicleService.rejectVehicle(eq(1L), eq(adminId), eq(adminName), eq(reason)))
                    .thenReturn(rejectedWithReason);

            mockMvc.perform(post("/api/v1/admin/vehicles/1/reject")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rejectRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("NEEDS_FIXES"))
                    .andExpect(jsonPath("$.rejectionReason").value(reason));

            verify(vehicleService, times(1)).rejectVehicle(eq(1L), eq(adminId), eq(adminName), eq(reason));
        }

        // Добавьте вспомогательный метод в тестовый класс
        private Vehicle createRejectedVehicleWithReason(String reason) {
            Vehicle vehicle = new Vehicle(
                    "BMW", "M5", 2023, "WBSDE91070CZ12345",
                    VehicleType.SEDAN, diagnosticId, diagnosticName
            );
            vehicle.setId(1L);
            vehicle.setLicensePlate("A123BC");
            vehicle.setColor("black");
            vehicle.setMileage(10000);
            vehicle.setEngineCapacity(4.4);
            vehicle.setFuelType(FuelType.PETROL);
            vehicle.setTransmission("auto");
            vehicle.setDescription("Test car");
            vehicle.submitForReview();
            vehicle.reject(adminId, adminName, reason);
            return vehicle;
        }

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Пустая причина - 400")
        void rejectVehicle_EmptyReason_ShouldReturnBadRequest() throws Exception {
            VehicleRejectRequest rejectRequest = new VehicleRejectRequest(1L, adminId, adminName, "");

            mockMvc.perform(post("/api/v1/admin/vehicles/1/reject")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rejectRequest)))
                    .andExpect(status().isBadRequest());

            verify(vehicleService, never()).rejectVehicle(anyLong(), anyLong(), anyString(), anyString());
        }

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Null причина - 400")
        void rejectVehicle_NullReason_ShouldReturnBadRequest() throws Exception {
            VehicleRejectRequest rejectRequest = new VehicleRejectRequest(1L, adminId, adminName, null);

            mockMvc.perform(post("/api/v1/admin/vehicles/1/reject")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rejectRequest)))
                    .andExpect(status().isBadRequest());

            verify(vehicleService, never()).rejectVehicle(anyLong(), anyLong(), anyString(), anyString());
        }
    }

    // ==================== GET APPROVED VEHICLES TESTS ====================

    @Nested
    @DisplayName("GET /api/admin/vehicles/approved - Получение одобренных")
    class GetApprovedVehiclesTests {

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Успешное получение списка")
        void getApprovedVehicles_ShouldReturnList() throws Exception {
            when(vehicleService.getVehiclesByStatus(VehicleStatus.APPROVED))
                    .thenReturn(List.of(approvedVehicle, inAuctionVehicle));

            mockMvc.perform(get("/api/v1/admin/vehicles/approved"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].status").value("APPROVED"))
                    .andExpect(jsonPath("$[1].status").value("IN_AUCTION"));

            verify(vehicleService, times(1)).getVehiclesByStatus(VehicleStatus.APPROVED);
        }

        @Test
        @WithMockCustomUser(userId = 2L, username = "user", roles = {"USER"})
        @DisplayName("Доступ запрещён для не-админа")
        void getApprovedVehicles_ByNonAdmin_ShouldReturnForbidden() throws Exception {
            mockMvc.perform(get("/api/v1/admin/vehicles/approved"))
                    .andExpect(status().isForbidden());

            verify(vehicleService, never()).getVehiclesByStatus(any());
        }
    }

    // ==================== GET IN AUCTION VEHICLES TESTS ====================

    @Nested
    @DisplayName("GET /api/admin/vehicles/in-auction - Получение в аукционе")
    class GetInAuctionVehiclesTests {

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Успешное получение списка")
        void getInAuctionVehicles_ShouldReturnList() throws Exception {
            when(vehicleService.getVehiclesByStatus(VehicleStatus.IN_AUCTION))
                    .thenReturn(List.of(inAuctionVehicle));

            mockMvc.perform(get("/api/v1/admin/vehicles/in-auction"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].status").value("IN_AUCTION"));

            verify(vehicleService, times(1)).getVehiclesByStatus(VehicleStatus.IN_AUCTION);
        }

        @Test
        @WithMockCustomUser(userId = 2L, username = "user", roles = {"USER"})
        @DisplayName("Доступ запрещён для не-админа")
        void getInAuctionVehicles_ByNonAdmin_ShouldReturnForbidden() throws Exception {
            mockMvc.perform(get("/api/v1/admin/vehicles/in-auction"))
                    .andExpect(status().isForbidden());

            verify(vehicleService, never()).getVehiclesByStatus(any());
        }
    }

    // ==================== GET NEEDS REDIAGNOSTIC VEHICLES TESTS ====================

    @Nested
    @DisplayName("GET /api/admin/vehicles/needs-rediagnostic - Получение требующих повторной диагностики")
    class GetNeedsRediagnosticVehiclesTests {

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Успешное получение списка")
        void getNeedsRediagnosticVehicles_ShouldReturnList() throws Exception {
            when(vehicleService.getVehiclesByStatus(VehicleStatus.NEEDS_REDIAGNOSTIC))
                    .thenReturn(List.of(needsRediagnosticVehicle));

            mockMvc.perform(get("/api/v1/admin/vehicles/needs-rediagnostic"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].status").value("NEEDS_REDIAGNOSTIC"));

            // Guards against a copy/paste mistake calling the wrong VehicleStatus constant
            // (e.g. PENDING_REVIEW, NEEDS_FIXES, APPROVED, IN_AUCTION).
            verify(vehicleService, times(1)).getVehiclesByStatus(VehicleStatus.NEEDS_REDIAGNOSTIC);
        }

        @Test
        @WithMockCustomUser(userId = 1L, username = "admin", roles = {"ADMIN"})
        @DisplayName("Пустой список")
        void getNeedsRediagnosticVehicles_EmptyList_ShouldReturnEmptyArray() throws Exception {
            when(vehicleService.getVehiclesByStatus(VehicleStatus.NEEDS_REDIAGNOSTIC))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/v1/admin/vehicles/needs-rediagnostic"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(0));

            verify(vehicleService, times(1)).getVehiclesByStatus(VehicleStatus.NEEDS_REDIAGNOSTIC);
        }

        @Test
        @WithMockCustomUser(userId = 2L, username = "user", roles = {"USER"})
        @DisplayName("Доступ запрещён для не-админа")
        void getNeedsRediagnosticVehicles_ByNonAdmin_ShouldReturnForbidden() throws Exception {
            mockMvc.perform(get("/api/v1/admin/vehicles/needs-rediagnostic"))
                    .andExpect(status().isForbidden());

            verify(vehicleService, never()).getVehiclesByStatus(any());
        }
    }
}