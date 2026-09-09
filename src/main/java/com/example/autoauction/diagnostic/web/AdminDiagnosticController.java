package com.example.autoauction.diagnostic.web;

import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.diagnostic.application.DiagnosticRequestService;
import com.example.autoauction.diagnostic.application.InspectionTemplateService;
import com.example.autoauction.diagnostic.commitment.application.SellerDiagnosticCommitmentFinalizationService;
import com.example.autoauction.diagnostic.domain.DiagnosticRejectionCategory;
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
    private final SellerDiagnosticCommitmentFinalizationService finalizationService;
    private final com.example.autoauction.diagnostic.media.application.LegacyMediaMigrationService legacyMediaMigrationService;
    private final com.example.autoauction.diagnostic.media.application.VideoProcessingService videoProcessingService;

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
            @RequestParam DiagnosticRejectionCategory category,
            @CurrentUser UserPrincipal currentUser
    ) {
        DiagnosticRequestResponse response = requestService.rejectReport(id, currentUser.getUserId(), reason, category);

        // PHASE 3B: intentionally a SEPARATE call, not nested inside rejectReport's own
        // transaction - AdminDiagnosticController is itself not @Transactional, so by the
        // time this line runs, rejectReport's transaction has already fully committed
        // (REJECTED status + AWAITING_FINALIZATION durably persisted). finalizeAsOutcomeB
        // therefore starts its own genuinely separate transaction (Spring's REQUIRED
        // propagation creates a new one when none is active). A finalization failure here
        // must never undo the admin's already-valid rejection decision - caught and logged,
        // mirroring the existing best-effort style already used a few lines away in
        // approveReport's vehicle-auto-creation step. Only VEHICLE_CONDITION maps to
        // Outcome B in this phase; SELLER_VIOLATION intentionally stays AWAITING_FINALIZATION
        // (Outcome C is not implemented).
        //
        // PHASE 3B corrective slice: deliberately KEEPING this try/catch exactly as-is - its
        // reasoning (a ledger hiccup must never undo an already-valid admin decision) is
        // unaffected by the new /retry-finalization endpoint below. A caught failure here is
        // no longer a dead end: it leaves the commitment durably AWAITING_FINALIZATION,
        // visible and recoverable via that endpoint - this comment records that this was an
        // active decision, not an oversight.
        if (category == DiagnosticRejectionCategory.VEHICLE_CONDITION) {
            try {
                finalizationService.finalizeAsOutcomeB(id);
            } catch (Exception e) {
                log.error("Outcome B finalization failed for request {} after successful rejection: {}",
                        id, e.getMessage(), e);
            }
        }

        return ResponseEntity.ok(response);
    }

    // ==================== PHASE 3B: ручной retry финализации Outcome B ====================

    /**
     * PHASE 3B corrective slice: manual admin recovery for a commitment left durably
     * AWAITING_FINALIZATION because the best-effort finalization attempt inside
     * rejectReport() failed (transient/infrastructure problem) and was only caught and
     * logged there - never rolled back the rejection itself. Mirrors
     * /media/{id}/retry-processing's exact shape: no try/catch here - a genuine failure
     * (not-found, not-eligible, unexpected state) propagates to GlobalExceptionHandler
     * (IllegalArgumentException -&gt; 404/400, IllegalStateException -&gt; 409), exactly like
     * every other admin action in this controller.
     *
     * Safe to call repeatedly: finalizeAsOutcomeB's own atomic claim-first mechanism is the
     * only idempotency guard - this endpoint does not duplicate it. Eligibility
     * (rejectionCategory == VEHICLE_CONDITION) is enforced inside finalizeAsOutcomeB itself,
     * not here - this method performs no business logic beyond invoking the application
     * service and returning the request's current state.
     */
    @PostMapping("/reports/{id}/retry-finalization")
    @Operation(summary = "Retry Outcome B financial finalization for a VEHICLE_CONDITION rejection (admin-triggered, idempotent)")
    public ResponseEntity<DiagnosticRequestResponse> retryFinalization(@PathVariable Long id) {
        finalizationService.finalizeAsOutcomeB(id);
        return ResponseEntity.ok(requestService.getRequestDetails(id));
    }

    // ==================== MEDIA-3.1B: исторический backfill шаблонов ====================

    /**
     * Одноразовый, идемпотентный backfill DiagnosticRequest.templateId для
     * заявок, созданных до MEDIA-3.1A. Никогда не угадывает шаблон - см.
     * DiagnosticRequestService.backfillHistoricalTemplateIds().
     */
    @PostMapping("/requests/backfill-template-ids")
    @Operation(summary = "Backfill historical inspection template bindings")
    public ResponseEntity<com.example.autoauction.diagnostic.web.dto.TemplateBackfillResult> backfillTemplateIds() {
        return ResponseEntity.ok(requestService.backfillHistoricalTemplateIds());
    }

    // ==================== MEDIA-3.3: миграция исторических медиа ====================

    /**
     * Одноразовая, идемпотентная миграция InspectionAnswer.photoUrls/videoUrls
     * в канонический DiagnosticMedia/R2 для заявок в статусе COMPLETED или
     * APPROVED. REJECTED намеренно исключён - см. LegacyMediaMigrationService
     * (класс-Javadoc): resetForRework() может вернуть REJECTED-заявку в
     * IN_PROGRESS, поэтому это не стабильный исторический снимок. Никогда не
     * угадывает недостающие данные - см. LegacyMediaMigrationService.migrate().
     */
    @PostMapping("/requests/legacy-media/migrate")
    @Operation(summary = "Migrate legacy diagnostic media into DiagnosticMedia/R2")
    public ResponseEntity<com.example.autoauction.diagnostic.media.web.dto.LegacyMediaMigrationResult> migrateLegacyMedia() {
        return ResponseEntity.ok(legacyMediaMigrationService.migrate());
    }

    // ==================== MEDIA-3.4 Phase 9: повторная обработка видео ====================

    /**
     * Ручной перезапуск видеообработки для записи в статусе FAILED.
     * Никогда не вызывается автоматически - планировщик (VideoProcessingScheduler)
     * никогда не трогает FAILED. Идемпотентно: повтор для уже-PENDING записи
     * безопасно возвращает её текущее состояние без ошибки - см.
     * VideoProcessingService.retryProcessing().
     */
    @PostMapping("/media/{id}/retry-processing")
    @Operation(summary = "Retry FAILED video processing (admin-triggered, idempotent)")
    public ResponseEntity<com.example.autoauction.diagnostic.media.web.dto.MediaResponse> retryProcessing(@PathVariable Long id) {
        return ResponseEntity.ok(videoProcessingService.retryProcessing(id));
    }
}