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
@RequestMapping("/api/v1/diagnostic/requests")
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
    public ResponseEntity<Page<DiagnosticRequestResponse>> getMyRequests(
            @PageableDefault(size = 20) Pageable pageable,
            @CurrentUser UserPrincipal currentUser
    ) {
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        log.info("GET /api/diagnostic-requests/my - user: {}", currentUser.getUserId());
        return ResponseEntity.ok(requestService.getUserRequests(currentUser.getUserId(), pageable));
    }

    /**
     * Получить детали заявки
     */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Получить детали заявки")
    public ResponseEntity<DiagnosticRequestResponse> getRequest(
            @PathVariable Long id,
            @CurrentUser UserPrincipal currentUser
    ) {
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        DiagnosticRequestResponse response = requestService.getRequestDetails(id);

        boolean isOwner = response.getUserId().equals(currentUser.getUserId());
        boolean isAdmin = currentUser.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        // Диагностик видит заявку только если он назначен на неё
        boolean isAssignedDiagnostician = currentUser.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_DIAGNOSTIC"))
                && currentUser.getUserId().equals(response.getAssignedTo());

        if (!isOwner && !isAdmin && !isAssignedDiagnostician) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Отозвать заявку (только PENDING, только владелец)
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Отозвать заявку на диагностику")
    public ResponseEntity<Void> cancelRequest(
            @PathVariable Long id,
            @CurrentUser UserPrincipal currentUser
    ) {
        requestService.cancelRequest(id, currentUser.getUserId());
        return ResponseEntity.noContent().build();
    }
}