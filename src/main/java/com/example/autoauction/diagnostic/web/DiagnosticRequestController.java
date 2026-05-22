package com.example.autoauction.diagnostic.web;

import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.web.dto.CreateDiagnosticRequest;
import com.example.autoauction.diagnostic.web.dto.DiagnosticRequestResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/diagnostic-requests")
@RequiredArgsConstructor
@Tag(name = "Заявки на диагностику", description = "Управление заявками на диагностику")
public class DiagnosticRequestController {

    private final DiagnosticRequestService requestService;

    /**
     * Создать заявку на диагностику (покупатель)
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Создать заявку на диагностику")
    public ResponseEntity<DiagnosticRequestResponse> createRequest(
            @Valid @RequestBody CreateDiagnosticRequest request,
            @CurrentUser UserPrincipal currentUser
    ) {
        log.info("POST /api/diagnostic-requests - user: {}", currentUser.getUserId());
        DiagnosticRequestResponse response = requestService.createRequest(request, currentUser.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Получить мои заявки (покупатель)
     */
    @GetMapping("/my")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Получить мои заявки")
    public Page<DiagnosticRequestResponse> getMyRequests(
            @PageableDefault(size = 20) Pageable pageable,
            @CurrentUser UserPrincipal currentUser
    ) {
        log.info("GET /api/diagnostic-requests/my - user: {}", currentUser.getUserId());
        return requestService.getUserRequests(currentUser.getUserId(), pageable);
    }

    /**
     * Получить детали заявки (покупатель)
     */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Получить детали заявки")
    public ResponseEntity<DiagnosticRequestResponse> getRequest(
            @PathVariable Long id,
            @CurrentUser UserPrincipal currentUser
    ) {
        log.info("GET /api/diagnostic-requests/{} - user: {}", id, currentUser.getUserId());
        DiagnosticRequestResponse response = requestService.getRequestDetails(id);

        // Проверяем, что пользователь имеет право просматривать заявку
        if (!response.getUserId().equals(currentUser.getUserId()) &&
                !currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return ResponseEntity.ok(response);
    }
}