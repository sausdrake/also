package com.example.autoauction.payment.web.dto;

import com.example.autoauction.payment.domain.Payment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Phase 26: admin-only, read-only observability row for a payment whose provider success was
 * observed after the payment had already expired ({@code lateSuccessAfterExpiry=true}). Purely
 * informational - the credit already happened correctly and exactly once (see {@link
 * Payment#markSucceeded}); this DTO exists only so an admin can identify and inspect the fact,
 * never to correct or re-process anything. Deliberately excludes {@code succeededAt} (no such
 * field exists - {@code updatedAt} already is the success instant for a terminal SUCCEEDED row),
 * provider raw payload, reconciliation fields, deposit fields, and user profile data.
 */
@Schema(description = "Платёж с поздним успешным подтверждением поставщика (только для администратора)")
public record AdminLateSuccessPaymentResponse(
        Long id,
        Long userId,
        BigDecimal amountRequested,
        String currency,
        BigDecimal amountConfirmed,
        String providerTransactionId,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime updatedAt,
        boolean lateSuccessAfterExpiry
) {

    public static AdminLateSuccessPaymentResponse fromDomain(Payment p) {
        return new AdminLateSuccessPaymentResponse(
                p.getId(),
                p.getUserId(),
                p.getAmountRequested(),
                p.getCurrency(),
                p.getAmountConfirmed(),
                p.getProviderTransactionId(),
                p.getStatus().name(),
                p.getCreatedAt(),
                p.getExpiresAt(),
                p.getUpdatedAt(),
                p.isLateSuccessAfterExpiry());
    }
}
