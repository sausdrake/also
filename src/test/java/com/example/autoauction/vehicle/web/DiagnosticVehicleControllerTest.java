// vehicle/web/DiagnosticVehicleControllerTest.java
package com.example.autoauction.vehicle.web;

import com.example.autoauction.auth.infrastructure.security.WithMockCustomUser;
import com.example.autoauction.vehicle.application.VehicleService;
import com.example.autoauction.vehicle.domain.FuelType;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.domain.VehicleType;
import com.example.autoauction.vehicle.web.dto.VehicleCreateRequest;
import com.example.autoauction.vehicle.web.dto.VehicleResponse;
import com.example.autoauction.vehicle.web.dto.VehicleUpdateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = DiagnosticVehicleController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = com.example.autoauction.auth.infrastructure.security.JwtAuthenticationFilter.class
        )
)
@Import(com.example.autoauction.admin.web.TestSecurityConfig.class)
@DisplayName("Тесты контроллера диагностика")
class DiagnosticVehicleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VehicleService vehicleService;

    @Autowired
    private ObjectMapper objectMapper;

    private VehicleCreateRequest createRequest;
    private VehicleUpdateRequest updateRequest;
    private VehicleResponse vehicleResponse;
    private final Long diagnosticId = 1L;
    private final String diagnosticName = "diagnostic";

    @BeforeEach
    void setUp() {
        objectMapper.registerModule(new JavaTimeModule());

        createRequest = new VehicleCreateRequest(
                "BMW", "M5", 2023, "WBSDE91070CZ12345",
                VehicleType.SEDAN, "A123BC", "black", 10000,
                4.4, FuelType.PETROL, "auto", "Test car"
        );

        updateRequest = new VehicleUpdateRequest(
                "B456CD", "white", 15000, 4.4,
                FuelType.PETROL, "auto", "Updated description"
        );

        OffsetDateTime now = OffsetDateTime.now();

        vehicleResponse = new VehicleResponse(
                1L, "BMW", "M5", 2023, "WBSDE91070CZ12345",
                "A123BC", VehicleType.SEDAN, "black", 10000, 4.4,
                FuelType.PETROL, "auto", "Test car",
                VehicleStatus.DRAFT, diagnosticId, diagnosticName,
                null, null, null, now, now, null, null
        );
    }

    @Test
    @WithMockCustomUser(userId = 1L, username = "diagnostic", roles = {"DIAGNOSTIC"})
    @DisplayName("Создание черновика - успех")
    void createDraft_ShouldReturnCreated() throws Exception {
        when(vehicleService.createDraft(any(VehicleCreateRequest.class), eq(diagnosticId), eq(diagnosticName)))
                .thenReturn(vehicleResponse);

        mockMvc.perform(post("/api/diagnostic/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.brand").value("BMW"))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        verify(vehicleService, times(1)).createDraft(any(VehicleCreateRequest.class), eq(diagnosticId), eq(diagnosticName));
    }

    @Test
    @WithMockCustomUser(userId = 1L, username = "diagnostic", roles = {"DIAGNOSTIC"})
    @DisplayName("Получение моих автомобилей - успех")
    void getMyVehicles_ShouldReturnList() throws Exception {
        when(vehicleService.getMyVehicles(eq(diagnosticId), eq(VehicleStatus.DRAFT)))
                .thenReturn(List.of(vehicleResponse));

        mockMvc.perform(get("/api/diagnostic/vehicles/my")
                        .param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].brand").value("BMW"));

        verify(vehicleService, times(1)).getMyVehicles(eq(diagnosticId), eq(VehicleStatus.DRAFT));
    }

    @Test
    @WithMockCustomUser(userId = 1L, username = "diagnostic", roles = {"DIAGNOSTIC"})
    @DisplayName("Получение автомобиля по ID - успех")
    void getVehicle_ShouldReturnVehicle() throws Exception {
        when(vehicleService.getVehicle(1L, diagnosticId)).thenReturn(vehicleResponse);

        mockMvc.perform(get("/api/diagnostic/vehicles/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.brand").value("BMW"));

        verify(vehicleService, times(1)).getVehicle(1L, diagnosticId);
    }

    @Test
    @WithMockCustomUser(userId = 1L, username = "diagnostic", roles = {"DIAGNOSTIC"})
    @DisplayName("Обновление черновика - успех")
    void updateDraft_ShouldReturnUpdatedVehicle() throws Exception {
        when(vehicleService.updateDraft(eq(1L), any(VehicleUpdateRequest.class), eq(diagnosticId)))
                .thenReturn(vehicleResponse);

        mockMvc.perform(put("/api/diagnostic/vehicles/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.brand").value("BMW"));

        verify(vehicleService, times(1)).updateDraft(eq(1L), any(VehicleUpdateRequest.class), eq(diagnosticId));
    }

    @Test
    @WithMockCustomUser(userId = 1L, username = "diagnostic", roles = {"DIAGNOSTIC"})
    @DisplayName("Отправка на проверку - успех")
    void submitForReview_ShouldReturnSubmittedVehicle() throws Exception {
        when(vehicleService.submitForReview(1L, diagnosticId)).thenReturn(vehicleResponse);

        mockMvc.perform(post("/api/diagnostic/vehicles/1/submit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.brand").value("BMW"));

        verify(vehicleService, times(1)).submitForReview(1L, diagnosticId);
    }

    @Test
    @WithMockCustomUser(userId = 1L, username = "diagnostic", roles = {"DIAGNOSTIC"})
    @DisplayName("Удаление черновика - успех")
    void deleteDraft_ShouldReturnNoContent() throws Exception {
        doNothing().when(vehicleService).deleteDraft(1L, diagnosticId);

        mockMvc.perform(delete("/api/diagnostic/vehicles/1"))
                .andExpect(status().isNoContent());

        verify(vehicleService, times(1)).deleteDraft(1L, diagnosticId);
    }
}