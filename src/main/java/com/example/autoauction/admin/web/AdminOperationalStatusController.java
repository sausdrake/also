package com.example.autoauction.admin.web;

import com.example.autoauction.admin.application.OperationalStatusService;
import com.example.autoauction.admin.web.dto.AdminOperationalStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 73 (G-N4): admin-only, read-only operational status endpoint. URL-level authorization is
 * already provided by {@code SecurityConfig}'s {@code /api/v1/admin/**} matcher; the class-level
 * {@code @PreAuthorize} is the same defense-in-depth double-layer used by every other {@code
 * Admin*Controller} in this codebase - no {@code SecurityConfig} change was needed or made for
 * this endpoint.
 */
@RestController
@RequestMapping("/api/v1/admin/ops")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Операционный статус", description = "Агрегированные операционные сигналы (только ADMIN, только чтение)")
public class AdminOperationalStatusController {

    private final OperationalStatusService operationalStatusService;

    public AdminOperationalStatusController(OperationalStatusService operationalStatusService) {
        this.operationalStatusService = operationalStatusService;
    }

    @GetMapping("/status")
    @Operation(summary = "Агрегированный операционный статус (планировщики, дела сверки)")
    public AdminOperationalStatusResponse getStatus() {
        return operationalStatusService.getStatus();
    }
}
