// admin/web/AdminAuctionController.java
package com.example.autoauction.admin.web;

import com.example.autoauction.admin.application.AdminAuctionService;
import com.example.autoauction.admin.web.dto.CancelAuctionRequest;
import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.auction.domain.BidStepPolicy;
import com.example.autoauction.auction.web.dto.AuctionCreateRequest;
import com.example.autoauction.auction.web.dto.AuctionResponse;
import com.example.autoauction.auction.web.dto.BidStepPreviewResponse;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin/auctions")
@PreAuthorize("hasRole('ADMIN')")
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

    @GetMapping("/bid-step")
    @Operation(summary = "Рассчитать минимальный шаг ставки и минимальную первую ставку по стартовой цене")
    public ResponseEntity<BidStepPreviewResponse> bidStep(@RequestParam BigDecimal startingPrice) {
        // AUCTION-01: the same BidStepPolicy that createAuction stores - the form never repeats the formula
        if (!BidStepPolicy.isStorableMoney(startingPrice)) {
            throw new IllegalArgumentException(
                    "Стартовая цена: больше 0, не больше 99999999.99 и не больше двух знаков после запятой");
        }
        BigDecimal step = BidStepPolicy.minimumBidStep(startingPrice);
        return ResponseEntity.ok(new BidStepPreviewResponse(startingPrice, step,
                BidStepPolicy.smallestValidBid(startingPrice.add(step))));
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
            @Valid @RequestBody(required = false) CancelAuctionRequest request,
            @Parameter(hidden = true)
            @CurrentUser UserPrincipal currentUser
    ) {
        Long adminId = currentUser.getUserId();
        List<Long> violations = request == null || request.violationDefinitionIds() == null
                ? List.of() : request.violationDefinitionIds();
        log.info("Admin {} cancelling auction {} ({} seller violation(s))", adminId, id, violations.size());

        AuctionResponse response = adminAuctionService.cancelAuction(id, adminId, violations);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/resolve-no-sale")
    @Operation(summary = "Вернуть гарантию продавца и отправить автомобиль на повторную диагностику по аукциону без продажи (EXPIRED/CANCELLED)")
    public ResponseEntity<AuctionResponse> resolveNoSale(
            @Parameter(description = "ID аукциона", required = true)
            @PathVariable Long id,
            @Parameter(hidden = true)
            @CurrentUser UserPrincipal currentUser
    ) {
        Long adminId = currentUser.getUserId();
        log.info("Admin {} resolving no-sale consequences of auction {}", adminId, id);
        return ResponseEntity.ok(adminAuctionService.resolveNoSale(id, adminId));
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

    @PostMapping("/{id}/confirm-start")
    @Operation(summary = "Подтвердить запуск аукциона")
    public ResponseEntity<AuctionResponse> confirmStart(@PathVariable Long id) {
        return ResponseEntity.ok(adminAuctionService.confirmAuctionStart(id));
    }

    @PostMapping("/confirm-start")
    @Operation(summary = "Массовое подтверждение запуска аукционов")
    public ResponseEntity<List<AuctionResponse>> confirmStartBulk(
            @RequestBody Map<String, List<Long>> body) {
        List<Long> ids = body.get("auctionIds");
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("auctionIds не может быть пустым");
        }
        return ResponseEntity.ok(adminAuctionService.confirmAuctionStartBulk(ids));
    }

    @GetMapping("/vehicle/{vehicleId}")
    @Operation(summary = "Получить аукционы по ID автомобиля")
    public List<AuctionResponse> getAuctionsByVehicleId(
            @Parameter(description = "ID автомобиля", required = true)
            @PathVariable Long vehicleId
    ) {
        return adminAuctionService.getAuctionsByVehicleId(vehicleId);
    }

    @GetMapping("/{id}/participants")
    @Operation(summary = "Получить список участников аукциона")
    public ResponseEntity<List<Map<String, Object>>> getParticipants(@PathVariable Long id) {
        return ResponseEntity.ok(adminAuctionService.getParticipants(id));
    }

    @PostMapping("/{id}/force-unfreeze-all")
    @Operation(summary = "Принудительно разморозить депозиты всех участников (кроме победителя)")
    public ResponseEntity<Map<String, Object>> forceUnfreezeAll(@PathVariable Long id) {
        return ResponseEntity.ok(adminAuctionService.forceUnfreezeAll(id));
    }

    @PostMapping("/{id}/release-winner")
    @Operation(summary = "Освободить депозит победителя аукциона")
    public ResponseEntity<Void> releaseWinner(@PathVariable Long id) {
        adminAuctionService.releaseWinner(id);
        return ResponseEntity.ok().build();
    }
}