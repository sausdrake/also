package com.example.autoauction.payment.web;

import com.example.autoauction.payment.application.AdminReconciliationService;
import com.example.autoauction.payment.web.dto.AdminReconciliationCaseDetailResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 20: admin-only, strictly read-only reconciliation case inspection. URL-level
 * authorization is already provided by SecurityConfig's {@code /api/v1/admin/**} matcher; the
 * class-level {@code @PreAuthorize} is the same defense-in-depth double-layer used by every
 * other {@code Admin*Controller} in this codebase (e.g. {@code AdminIdentityController}). No
 * per-resource ownership check is added, matching this codebase's established convention that
 * ADMIN has blanket read access (Phase 19 §7) - there is no narrower "owns this case" concept.
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
}
