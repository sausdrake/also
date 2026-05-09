// admin/web/AdminAuctionController.java
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/admin/auctions")
@Tag(name = "Администрирование аукционов", description = "Управление аукционами (только для администраторов)")
public class AdminAuctionController {

    private final AdminAuctionService adminAuctionService;

    private static final Pattern ALLOWED_STATUS_PATTERN = Pattern.compile("^[A-Z_]+$");

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
    @Operation(summary = "Получить все аукционы (с пагинацией)")
    public Page<AuctionResponse> getAllAuctions(
            @Parameter(description = "Номер страницы (0-based)")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Размер страницы")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Сортировка: поле,направление (asc/desc)")
            @RequestParam(defaultValue = "id,asc") String[] sort
    ) {
        Sort.Direction direction = sort.length > 1 && sort[1].equalsIgnoreCase("desc")
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sort[0]));
        return adminAuctionService.getAllAuctions(pageable);
    }

    @GetMapping("/all")
    @Operation(summary = "Получить все аукционы (без пагинации - для совместимости)")
    public List<AuctionResponse> getAllAuctionsLegacy() {
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
    @Operation(summary = "Получить аукционы по статусу (с пагинацией)")
    public Page<AuctionResponse> getAuctionsByStatus(
            @RequestParam String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(hidden = true)
            @CurrentUser UserPrincipal currentUser
    ) {
        if (status == null || status.trim().isEmpty()) {
            throw new IllegalArgumentException("Статус не может быть пустым");
        }

        String sanitizedStatus = status.trim().toUpperCase();

        if (!ALLOWED_STATUS_PATTERN.matcher(sanitizedStatus).matches()) {
            log.warn("Попытка использования недопустимых символов в статусе: {}", status);
            throw new IllegalArgumentException("Статус должен содержать только буквы и символ подчеркивания");
        }

        Pageable pageable = PageRequest.of(page, size);
        return adminAuctionService.getAuctionsByStatus(sanitizedStatus, pageable);
    }

    @GetMapping("/by-status-legacy")
    @Operation(summary = "Получить аукционы по статусу (без пагинации - для совместимости)")
    public List<AuctionResponse> getAuctionsByStatusLegacy(
            @RequestParam String status
    ) {
        if (status == null || status.trim().isEmpty()) {
            throw new IllegalArgumentException("Статус не может быть пустым");
        }

        String sanitizedStatus = status.trim().toUpperCase();

        if (!ALLOWED_STATUS_PATTERN.matcher(sanitizedStatus).matches()) {
            log.warn("Попытка использования недопустимых символов в статусе: {}", status);
            throw new IllegalArgumentException("Статус должен содержать только буквы и символ подчеркивания");
        }

        return adminAuctionService.getAuctionsByStatus(sanitizedStatus);
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