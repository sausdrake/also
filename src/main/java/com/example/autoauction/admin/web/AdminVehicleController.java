package com.example.autoauction.admin.web;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.vehicle.application.VehicleService;
import com.example.autoauction.vehicle.domain.VehicleStatus;
import com.example.autoauction.vehicle.web.dto.VehicleRejectRequest;
import com.example.autoauction.vehicle.web.dto.VehicleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin/vehicles")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Администрирование автомобилей", description = "Управление отчетами об автомобилях (для администраторов)")
public class AdminVehicleController {

    private final VehicleService vehicleService;

    public AdminVehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @GetMapping("/pending")
    @Operation(summary = "Получить отчеты на проверке")
    public List<VehicleResponse> getPendingVehicles() {
        return vehicleService.getVehiclesByStatus(VehicleStatus.PENDING_REVIEW);
    }

    @GetMapping("/needs-fixes")
    @Operation(summary = "Получить отчеты, требующие доработки")
    public List<VehicleResponse> getNeedsFixesVehicles() {
        return vehicleService.getVehiclesByStatus(VehicleStatus.NEEDS_FIXES);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Получить детали отчета")
    public ResponseEntity<VehicleResponse> getVehicleDetails(@PathVariable Long id) {
        VehicleResponse response = vehicleService.getVehicleDetails(id);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Одобрить отчет")
    public ResponseEntity<VehicleResponse> approveVehicle(
            @Parameter(description = "ID автомобиля", required = true)
            @PathVariable Long id,
            @Parameter(hidden = true)
            @CurrentUser UserPrincipal currentUser
    ) {
        Long adminId = currentUser.getUserId();
        String adminName = currentUser.getUsername();

        VehicleResponse response = vehicleService.approveVehicle(id, adminId, adminName);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Отклонить отчет")
    public ResponseEntity<VehicleResponse> rejectVehicle(
            @Parameter(description = "ID автомобиля", required = true)
            @PathVariable Long id,
            @Valid @RequestBody VehicleRejectRequest request,
            @Parameter(hidden = true)
            @CurrentUser UserPrincipal currentUser
    ) {
        Long adminId = currentUser.getUserId();
        String adminName = currentUser.getUsername();

        VehicleResponse response = vehicleService.rejectVehicle(id, adminId, adminName, request.reason());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/approved")
    @Operation(summary = "Получить одобренные отчеты")
    public List<VehicleResponse> getApprovedVehicles() {
        return vehicleService.getVehiclesByStatus(VehicleStatus.APPROVED);
    }

    @GetMapping("/in-auction")
    @Operation(summary = "Получить отчеты в аукционе")
    public List<VehicleResponse> getInAuctionVehicles() {
        return vehicleService.getVehiclesByStatus(VehicleStatus.IN_AUCTION);
    }

    @GetMapping("/needs-rediagnostic")
    @Operation(summary = "Получить автомобили, требующие повторной диагностики")
    public List<VehicleResponse> getNeedsRediagnosticVehicles() {
        return vehicleService.getVehiclesByStatus(VehicleStatus.NEEDS_REDIAGNOSTIC);
    }
}