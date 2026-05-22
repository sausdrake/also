package com.example.autoauction.diagnostic.web;

import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.web.dto.DiagnosticRequestResponse;
import com.example.autoauction.diagnostic.web.dto.SubmitInspectionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/diagnostic-work")
@RequiredArgsConstructor
@Tag(name = "Работа диагноста", description = "Управление заявками для диагноста")
@PreAuthorize("hasRole('DIAGNOSTIC')")
public class DiagnosticWorkController {

    private final DiagnosticRequestService requestService;

    /**
     * Получить доступные заявки (PENDING)
     */
    @GetMapping("/pending")
    @Operation(summary = "Получить доступные заявки")
    @PreAuthorize("hasRole('DIAGNOSTIC')")
    public ResponseEntity<List<DiagnosticRequestResponse>> getPendingRequests(
            @CurrentUser UserPrincipal currentUser) {
        log.info("GET /api/diagnostic/work/pending");

        return ResponseEntity.ok(requestService.getPendingRequests());
    }

    /**
     * Взять заявку в работу
     */
    @PostMapping("/{requestId}/assign")
    @Operation(summary = "Взять заявку в работу")
    public ResponseEntity<DiagnosticRequestResponse> assignRequest(
            @PathVariable Long requestId,
            @CurrentUser UserPrincipal currentUser
    ) {
        log.info("POST /api/diagnostic/work/{}/assign - diagnostic: {}", requestId, currentUser.getUserId());
        DiagnosticRequestResponse response = requestService.assignToDiagnostic(requestId, currentUser.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * Получить заявку с шаблоном для заполнения
     */
    @GetMapping("/{requestId}")
    @Operation(summary = "Получить заявку с шаблоном для заполнения")
    public ResponseEntity<DiagnosticRequestResponse> getRequestWithTemplate(
            @PathVariable Long requestId,
            @CurrentUser UserPrincipal currentUser
    ) {
        log.info("GET /api/diagnostic/work/{} - diagnostic: {}", requestId, currentUser.getUserId());
        DiagnosticRequestResponse response = requestService.getRequestWithTemplate(requestId, currentUser.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * Сохранить ответы на анкету (черновик или отправка)
     */
    @PostMapping("/{requestId}/answers")
    @Operation(summary = "Сохранить ответы на анкету")
    public ResponseEntity<Void> saveAnswers(
            @PathVariable Long requestId,
            @Valid @RequestBody SubmitInspectionRequest request,
            @CurrentUser UserPrincipal currentUser
    ) {
        log.info("POST /api/diagnostic/work/{}/answers - diagnostic: {}, submit: {}",
                requestId, currentUser.getUserId(), request.isSubmit());
        requestService.saveInspectionAnswers(requestId, currentUser.getUserId(), request);
        return ResponseEntity.ok().build();
    }

    /**
     * Получить мои заявки (в работе и завершённые)
     */
    @GetMapping("/my")
    @Operation(summary = "Получить мои заявки")
    public Page<DiagnosticRequestResponse> getMyRequests(
            @PageableDefault(size = 20) Pageable pageable,
            @CurrentUser UserPrincipal currentUser
    ) {
        log.info("GET /api/diagnostic/work/my - diagnostic: {}", currentUser.getUserId());
        return requestService.getDiagnosticRequests(currentUser.getUserId(), pageable);
    }
}