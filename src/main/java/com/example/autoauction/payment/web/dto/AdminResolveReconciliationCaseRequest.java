package com.example.autoauction.payment.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Phase 24: request body for administrative reconciliation-case closure. Deliberately has no
 * {@code adminId} field - the resolving admin is always {@code @CurrentUser}, never
 * client-supplied - and no {@code resolvedAt} - always server-generated. {@code resolutionNote}
 * has no {@code @NotBlank}: {@link com.example.autoauction.payment.domain.ReconciliationCase#resolve}
 * itself permits a null/blank note, and no reconciliation-specific contract establishes a
 * stricter rule.
 */
@Schema(description = "Закрытие дела сверки (только административные метаданные)")
public record AdminResolveReconciliationCaseRequest(String resolutionNote) {
}
