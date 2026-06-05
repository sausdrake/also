package com.example.autoauction.diagnostic.web;

import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.application.InspectionTemplateService;
import com.example.autoauction.diagnostic.domain.InspectionGroup;
import com.example.autoauction.diagnostic.domain.InspectionItem;
import com.example.autoauction.diagnostic.domain.InspectionTemplate;
import com.example.autoauction.diagnostic.web.dto.DiagnosticRequestResponse;
import com.example.autoauction.diagnostic.web.dto.InspectionGroupDto;
import com.example.autoauction.diagnostic.web.dto.InspectionItemDto;
import com.example.autoauction.diagnostic.web.dto.InspectionTemplateDto;
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
@RequestMapping("/api/v1/admin/diagnostic")
@RequiredArgsConstructor
@Tag(name = "Админ панель диагностики", description = "Управление шаблонами и проверка отчётов")
@PreAuthorize("hasRole('ADMIN')")
public class AdminDiagnosticController {

    private final InspectionTemplateService templateService;
    private final DiagnosticRequestService requestService;

    // ==================== Шаблоны ====================

    @GetMapping("/templates")
    @Operation(summary = "Получить все шаблоны")
    public Page<InspectionTemplate> getAllTemplates(@PageableDefault(size = 20) Pageable pageable) {
        return templateService.getAllTemplates(pageable);
    }

    @GetMapping("/templates/active")
    @Operation(summary = "Получить активные шаблоны")
    public Page<InspectionTemplate> getActiveTemplates(@PageableDefault(size = 20) Pageable pageable) {
        return templateService.getActiveTemplates(pageable);
    }

    @GetMapping("/templates/{id}")
    @Operation(summary = "Получить шаблон по ID")
    public ResponseEntity<InspectionTemplate> getTemplate(@PathVariable Long id) {
        return ResponseEntity.ok(templateService.getFullTemplateById(id));
    }

    @PostMapping("/templates")
    @Operation(summary = "Создать шаблон")
    public ResponseEntity<InspectionTemplate> createTemplate(
            @Valid @RequestBody InspectionTemplateDto dto,
            @CurrentUser UserPrincipal currentUser
    ) {
        InspectionTemplate template = templateService.createTemplate(dto, currentUser.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(template);
    }

    @PutMapping("/templates/{id}")
    @Operation(summary = "Обновить шаблон")
    public ResponseEntity<InspectionTemplate> updateTemplate(
            @PathVariable Long id,
            @Valid @RequestBody InspectionTemplateDto dto
    ) {
        return ResponseEntity.ok(templateService.updateTemplate(id, dto));
    }

    @DeleteMapping("/templates/{id}")
    @Operation(summary = "Удалить шаблон")
    public ResponseEntity<Void> deleteTemplate(@PathVariable Long id) {
        templateService.deleteTemplate(id);
        return ResponseEntity.noContent().build();
    }

    // ==================== Группы ====================

    @PostMapping("/templates/{templateId}/groups")
    @Operation(summary = "Добавить группу в шаблон")
    public ResponseEntity<InspectionGroup> addGroup(
            @PathVariable Long templateId,
            @Valid @RequestBody InspectionGroupDto dto
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(templateService.addGroup(templateId, dto));
    }

    @PutMapping("/groups/{groupId}")
    @Operation(summary = "Обновить группу")
    public ResponseEntity<InspectionGroup> updateGroup(
            @PathVariable Long groupId,
            @Valid @RequestBody InspectionGroupDto dto
    ) {
        return ResponseEntity.ok(templateService.updateGroup(groupId, dto));
    }

    @DeleteMapping("/groups/{groupId}")
    @Operation(summary = "Удалить группу")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long groupId) {
        templateService.deleteGroup(groupId);
        return ResponseEntity.noContent().build();
    }

    // ==================== Пункты ====================

    @PostMapping("/groups/{groupId}/items")
    @Operation(summary = "Добавить пункт в группу")
    public ResponseEntity<InspectionItem> addItem(
            @PathVariable Long groupId,
            @Valid @RequestBody InspectionItemDto dto
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(templateService.addItem(groupId, dto));
    }

    @PutMapping("/items/{itemId}")
    @Operation(summary = "Обновить пункт")
    public ResponseEntity<InspectionItem> updateItem(
            @PathVariable Long itemId,
            @Valid @RequestBody InspectionItemDto dto
    ) {
        return ResponseEntity.ok(templateService.updateItem(itemId, dto));
    }

    @DeleteMapping("/items/{itemId}")
    @Operation(summary = "Удалить пункт")
    public ResponseEntity<Void> deleteItem(@PathVariable Long itemId) {
        templateService.deleteItem(itemId);
        return ResponseEntity.noContent().build();
    }

    // ==================== Проверка отчётов ====================

    @GetMapping("/reports")
    @Operation(summary = "Получить все заявки на диагностику (все статусы)")
    public Page<DiagnosticRequestResponse> getAllReports(@PageableDefault(size = 20) Pageable pageable) {
        return requestService.getAllRequests(pageable);
    }

    @GetMapping("/reports/pending")
    @Operation(summary = "Получить отчёты на проверке (статус COMPLETED)")
    public Page<DiagnosticRequestResponse> getPendingReports(@PageableDefault(size = 20) Pageable pageable) {
        return requestService.getCompletedReports(pageable);
    }

    @GetMapping("/reports/{id}")
    @Operation(summary = "Получить отчёт для проверки")
    public ResponseEntity<DiagnosticRequestResponse> getReportForReview(@PathVariable Long id) {
        return ResponseEntity.ok(requestService.getRequestDetails(id));
    }

    @PostMapping("/reports/{id}/approve")
    @Operation(summary = "Одобрить отчёт диагноста")
    public ResponseEntity<DiagnosticRequestResponse> approveReport(
            @PathVariable Long id,
            @CurrentUser UserPrincipal currentUser
    ) {
        return ResponseEntity.ok(requestService.approveReport(id, currentUser.getUserId()));
    }

    @PostMapping("/reports/{id}/reject")
    @Operation(summary = "Отклонить отчёт диагноста с причиной")
    public ResponseEntity<DiagnosticRequestResponse> rejectReport(
            @PathVariable Long id,
            @RequestParam String reason,
            @CurrentUser UserPrincipal currentUser
    ) {
        return ResponseEntity.ok(requestService.rejectReport(id, currentUser.getUserId(), reason));
    }
}