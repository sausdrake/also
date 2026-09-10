package com.example.autoauction.sale.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Slice 1: the admin-selected failed-sale outcome plus the audit-only reason and (Outcome C
 * only) the selected violation-definition ids.
 *
 * Deliberately does NOT contain: {@code faultParty} (server-derived from {@code outcome}),
 * buyerId, payment amount, refundedAmount, penalty amount, or commission - none of those are
 * ever client-supplied.
 *
 * {@code violationDefinitionIds} is intentionally nullable/empty-allowed at this layer: there
 * is NO cross-field "outcome == C =&gt; non-empty" rule, because a historical auction with no
 * linked seller commitment legitimately resolves C with an empty list. The authoritative
 * conditional check lives in {@code SaleFailureResolutionOrchestrator}, and unknown / non-C
 * misuse is rejected by the existing resolution logic and the X3-E violation engine.
 */
public record FailRequest(
        @NotBlank(message = "outcome обязателен")
        String outcome,

        List<Long> violationDefinitionIds,

        @Size(max = 500, message = "reason не может быть длиннее 500 символов")
        String reason
) {
}
