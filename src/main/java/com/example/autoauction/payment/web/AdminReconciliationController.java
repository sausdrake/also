package com.example.autoauction.payment.web;

import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import com.example.autoauction.payment.application.AdminReconciliationService;
import com.example.autoauction.payment.domain.ReconciliationCaseStatus;
import com.example.autoauction.payment.domain.ReconciliationCaseType;
import com.example.autoauction.payment.web.dto.AdminReconciliationCaseDetailResponse;
import com.example.autoauction.payment.web.dto.AdminReconciliationCaseListItemResponse;
import com.example.autoauction.payment.web.dto.AdminResolveReconciliationCaseRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 20/22/24: admin-only reconciliation case inspection (detail/list, read-only) and
 * administrative case closure (resolve). URL-level authorization is already provided by
 * SecurityConfig's {@code /api/v1/admin/**} matcher; the class-level {@code @PreAuthorize} is
 * the same defense-in-depth double-layer used by every other {@code Admin*Controller} in this
 * codebase (e.g. {@code AdminIdentityController}). No per-resource ownership check is added,
 * matching this codebase's established convention that ADMIN has blanket read/write access
 * (Phase 19 §7) - there is no narrower "owns this case" concept. {@code resolve} is
 * administrative metadata closure only - it never touches Payment/Deposit data.
 */
@RestController
@RequestMapping("/api/v1/admin/reconciliation")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Администрирование сверки платежей", description = "Просмотр дел сверки платежей (только ADMIN, только чтение)")
public class AdminReconciliationController {

    private final AdminReconciliationService adminReconciliationService;

    public AdminReconciliationController(AdminReconciliationService adminReconciliationService) {
        this.adminReconciliationService = adminReconciliationService;
    }

    @GetMapping("/cases/{caseId}")
    @Operation(summary = "Просмотреть дело сверки платежа")
    public ResponseEntity<AdminReconciliationCaseDetailResponse> getCaseDetail(@PathVariable Long caseId) {
        return ResponseEntity.ok(adminReconciliationService.getCaseDetail(caseId));
    }

    @GetMapping("/cases")
    @Operation(summary = "Список дел сверки платежей (фильтры: status, type)")
    public List<AdminReconciliationCaseListItemResponse> listCases(
            @RequestParam(required = false) ReconciliationCaseStatus status,
            @RequestParam(required = false) ReconciliationCaseType type) {
        return adminReconciliationService.listCases(status, type);
    }

    @PostMapping("/cases/{caseId}/resolve")
    @Operation(summary = "Закрыть дело сверки (административные метаданные, без финансовой коррекции)")
    public ResponseEntity<AdminReconciliationCaseDetailResponse.CaseSummary> resolveCase(
            @PathVariable Long caseId,
            @RequestBody(required = false) AdminResolveReconciliationCaseRequest request,
            @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser) {
        Long adminId = currentUser.getUserId();
        String resolutionNote = request == null ? null : request.resolutionNote();
        return ResponseEntity.ok(adminReconciliationService.resolveCase(caseId, adminId, resolutionNote));
    }
}
