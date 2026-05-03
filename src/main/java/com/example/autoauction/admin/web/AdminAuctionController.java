package com.example.autoauction.admin.web;

import com.example.autoauction.admin.application.AdminAuctionService;
import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.auction.web.dto.AuctionCreateRequest;
import com.example.autoauction.auction.web.dto.AuctionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/admin/auctions")
@Tag(name = "Администрирование аукционов", description = "Управление аукционами (только для администраторов)")
public class AdminAuctionController {

    private final AdminAuctionService adminAuctionService;

    public AdminAuctionController(AdminAuctionService adminAuctionService) {
        this.adminAuctionService = adminAuctionService;
    }

    @PostMapping
    @Operation(summary = "Создать новый аукцион")
    public ResponseEntity<AuctionResponse> createAuction(
            @Valid @RequestBody AuctionCreateRequest request,
            @Parameter(hidden = true)
            @CurrentUser UserPrincipal currentUser
    ) {
        Long adminId = currentUser.getUserId();
        log.info("Admin {} creating auction for vehicle {}", adminId, request.vehicleId());

        AuctionResponse response = adminAuctionService.createAuction(request, adminId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "Получить все аукционы")
    public List<AuctionResponse> getAllAuctions() {
        return adminAuctionService.getAllAuctions();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Получить аукцион по ID")
    public ResponseEntity<AuctionResponse> getAuction(
            @Parameter(description = "ID аукциона", required = true)
            @PathVariable Long id
    ) {
        AuctionResponse response = adminAuctionService.getAuction(id);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/start")
    @Operation(summary = "Запустить аукцион")
    public ResponseEntity<AuctionResponse> startAuction(
            @Parameter(description = "ID аукциона", required = true)
            @PathVariable Long id,
            @Parameter(hidden = true)
            @CurrentUser UserPrincipal currentUser
    ) {
        Long adminId = currentUser.getUserId();
        log.info("Admin {} starting auction {}", adminId, id);

        AuctionResponse response = adminAuctionService.startAuction(id, adminId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Отменить аукцион")
    public ResponseEntity<AuctionResponse> cancelAuction(
            @Parameter(description = "ID аукциона", required = true)
            @PathVariable Long id,
            @Parameter(hidden = true)
            @CurrentUser UserPrincipal currentUser
    ) {
        Long adminId = currentUser.getUserId();
        log.info("Admin {} cancelling auction {}", adminId, id);

        AuctionResponse response = adminAuctionService.cancelAuction(id, adminId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "Принудительно завершить аукцион")
    public ResponseEntity<AuctionResponse> completeAuction(
            @Parameter(description = "ID аукциона", required = true)
            @PathVariable Long id,
            @Parameter(hidden = true)
            @CurrentUser UserPrincipal currentUser
    ) {
        Long adminId = currentUser.getUserId();
        log.info("Admin {} manually completing auction {}", adminId, id);

        AuctionResponse response = adminAuctionService.completeAuction(id, adminId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/by-status")
    public List<AuctionResponse> getAuctionsByStatus(@RequestParam String status) {
        if (status == null || status.trim().isEmpty()) {
            throw new IllegalArgumentException("Статус не может быть пустым");
        }

        if (!status.matches("^[A-Za-z_]+$")) {
            log.warn("Попытка использования недопустимых символов в статусе: {}", status);
            throw new IllegalArgumentException("Статус должен содержать только буквы и символ подчеркивания");
        }

        return adminAuctionService.getAuctionsByStatus(status);
    }

    @GetMapping("/vehicle/{vehicleId}")
    @Operation(summary = "Получить аукционы по ID автомобиля")
    public List<AuctionResponse> getAuctionsByVehicleId(
            @Parameter(description = "ID автомобиля", required = true)
            @PathVariable Long vehicleId
    ) {
        return adminAuctionService.getAuctionsByVehicleId(vehicleId);
    }
}