package com.example.autoauction.payment.web.dto;

import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.ReconciliationCase;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Phase 22: flat, list-only row - deliberately excludes {@code version}, {@code resolutionNote},
 * and all evidence/PaymentEvent/provider data (those remain detail-only, via {@link
 * AdminReconciliationCaseDetailResponse}). {@code paymentExists=false} with null {@code
 * requestedAmount}/{@code requestedCurrency} represents a broken case-to-payment reference
 * without dropping the row or failing the whole list - the same per-row-resilience principle
 * already used by {@code AdminUserController.listUsers(withBalance=true)}.
 */
@Schema(description = "Дело сверки платежа (строка списка, только для администратора)")
public record AdminReconciliationCaseListItemResponse(
        Long id,
        Long paymentId,
        boolean paymentExists,
        String type,
        String status,
        OffsetDateTime openedAt,
        OffsetDateTime resolvedAt,
        Long resolvedByAdminId,
        BigDecimal requestedAmount,
        String requestedCurrency
) {

    public static AdminReconciliationCaseListItemResponse withPayment(ReconciliationCase c, Payment payment) {
        return new AdminReconciliationCaseListItemResponse(
                c.getId(),
                c.getPaymentId(),
                true,
                c.getType().name(),
                c.getStatus().name(),
                c.getOpenedAt(),
                c.getResolvedAt(),
                c.getResolvedByAdminId(),
                payment.getAmountRequested(),
                payment.getCurrency());
    }

    public static AdminReconciliationCaseListItemResponse withoutPayment(ReconciliationCase c) {
        return new AdminReconciliationCaseListItemResponse(
                c.getId(),
                c.getPaymentId(),
                false,
                c.getType().name(),
                c.getStatus().name(),
                c.getOpenedAt(),
                c.getResolvedAt(),
                c.getResolvedByAdminId(),
                null,
                null);
    }
}
