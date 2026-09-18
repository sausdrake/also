package com.example.autoauction.payment.web;

import com.example.autoauction.payment.application.AdminPaymentService;
import com.example.autoauction.payment.web.dto.AdminLateSuccessPaymentResponse;
import com.example.autoauction.payment.web.dto.AdminStuckInitiationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 26: admin-only, strictly read-only payment observability. URL-level authorization is
 * already provided by SecurityConfig's {@code /api/v1/admin/**} matcher; the class-level
 * {@code @PreAuthorize} is the same defense-in-depth double-layer used by every other
 * {@code Admin*Controller} in this codebase. Deliberately separate from {@code
 * AdminReconciliationController} - a late-success payment has no {@code ReconciliationCase} in
 * the common case, so this capability cannot correctly live in that module.
 */
@RestController
@RequestMapping("/api/v1/admin/payments")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Администрирование платежей", description = "Просмотр платежей (только ADMIN, только чтение)")
public class AdminPaymentController {

    private final AdminPaymentService adminPaymentService;

    public AdminPaymentController(AdminPaymentService adminPaymentService) {
        this.adminPaymentService = adminPaymentService;
    }

    @GetMapping("/late-successes")
    @Operation(summary = "Платежи с поздним успешным подтверждением поставщика (после истечения срока)")
    public List<AdminLateSuccessPaymentResponse> listLateSuccesses() {
        return adminPaymentService.listLateSuccesses();
    }

    @GetMapping("/stuck-initiations")
    @Operation(summary = "Платежи CREATED с застрявшим восстановлением инициации (некорректное или отсутствующее свидетельство)")
    public List<AdminStuckInitiationResponse> listStuckInitiations() {
        return adminPaymentService.listStuckInitiations();
    }
}
