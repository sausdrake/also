package com.example.autoauction.diagnostic.web;

import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.application.InspectionTemplateService;
import com.example.autoauction.diagnostic.domain.InspectionTemplate;
import com.example.autoauction.diagnostic.web.dto.BindTemplateRequest;
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

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/diagnostic/work")
@RequiredArgsConstructor
@Tag(name = "Работа диагноста", description = "Управление заявками для диагноста")
@PreAuthorize("hasAnyRole('DIAGNOSTIC', 'ADMIN')")
public class DiagnosticWorkController {

    private final DiagnosticRequestService requestService;
    private final InspectionTemplateService templateService;

    /**
     * Получить доступные заявки (PENDING)
     */
    @GetMapping("/pending")
    @Operation(summary = "Получить доступные заявки")
    public ResponseEntity<List<DiagnosticRequestResponse>> getPendingRequests() {
        log.info("GET /api/v1/diagnostic/work/pending");
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
     * Получить список активных шаблонов для диагноста
     */
    @GetMapping("/templates")
    @Operation(summary = "Список активных шаблонов анкет")
    public ResponseEntity<List<InspectionTemplate>> getActiveTemplates() {
        List<InspectionTemplate> templates = templateService.getActiveTemplates(Pageable.unpaged()).getContent();
        return ResponseEntity.ok(templates);
    }

    /**
     * Получить заявку с привязанным шаблоном для заполнения.
     * MEDIA-3.1A: чисто read-only - шаблон должен быть уже привязан через
     * POST /{requestId}/template; здесь он больше не выбирается.
     */
    @GetMapping("/{requestId}")
    @Operation(summary = "Получить заявку с привязанным шаблоном для заполнения")
    public ResponseEntity<DiagnosticRequestResponse> getRequestWithTemplate(
            @PathVariable Long requestId,
            @CurrentUser UserPrincipal currentUser
    ) {
        log.info("GET /api/diagnostic/work/{} - diagnostic: {}", requestId, currentUser.getUserId());
        DiagnosticRequestResponse response = requestService.getRequestWithTemplate(requestId, currentUser.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * Явно привязать шаблон анкеты к заявке (один раз, необратимо).
     * MEDIA-3.1A.
     */
    @PostMapping("/{requestId}/template")
    @Operation(summary = "Привязать шаблон анкеты к заявке")
    public ResponseEntity<DiagnosticRequestResponse> bindTemplate(
            @PathVariable Long requestId,
            @Valid @RequestBody BindTemplateRequest request,
            @CurrentUser UserPrincipal currentUser
    ) {
        log.info("POST /api/diagnostic/work/{}/template - diagnostic: {}, templateId: {}",
                requestId, currentUser.getUserId(), request.getTemplateId());
        DiagnosticRequestResponse response = requestService.bindTemplate(requestId, currentUser.getUserId(), request.getTemplateId());
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
     * Исправить отклонённый отчёт (сбросить в IN_PROGRESS)
     */
    @PostMapping("/{requestId}/rework")
    @Operation(summary = "Исправить отклонённый отчёт")
    public ResponseEntity<DiagnosticRequestResponse> resetForRework(
            @PathVariable Long requestId,
            @CurrentUser UserPrincipal currentUser
    ) {
        log.info("POST /api/diagnostic/work/{}/rework - diagnostic: {}", requestId, currentUser.getUserId());
        return ResponseEntity.ok(requestService.resetForRework(requestId, currentUser.getUserId()));
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