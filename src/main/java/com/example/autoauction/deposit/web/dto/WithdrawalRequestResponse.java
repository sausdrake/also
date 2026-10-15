package com.example.autoauction.deposit.web.dto;

import com.example.autoauction.deposit.domain.WithdrawalRequest;
import com.example.autoauction.deposit.domain.WithdrawalRequestStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * F6: a withdrawal request as shown to its owner ({@link #forOwner}: no admin ids, no contact
 * data) or to an admin ({@link #forAdmin}: who processed it and how to reach the user).
 */
@Schema(description = "Заявка на вывод средств (ручная выплата)")
public record WithdrawalRequestResponse(
        Long id,
        Long userId,
        String username,
        String email,
        BigDecimal amount,
        WithdrawalRequestStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime processingStartedAt,
        Long processingStartedBy,
        OffsetDateTime processedAt,
        Long processedBy,
        String rejectionReason
) {
    public static WithdrawalRequestResponse forOwner(WithdrawalRequest r) {
        return new WithdrawalRequestResponse(r.getId(), r.getUserId(), null, null, r.getAmount(), r.getStatus(),
                r.getCreatedAt(), r.getProcessingStartedAt(), null, r.getProcessedAt(), null, r.getRejectionReason());
    }

    public static WithdrawalRequestResponse forAdmin(WithdrawalRequest r, String username, String email) {
        return new WithdrawalRequestResponse(r.getId(), r.getUserId(), username, email, r.getAmount(), r.getStatus(),
                r.getCreatedAt(), r.getProcessingStartedAt(), r.getProcessingStartedBy(), r.getProcessedAt(),
                r.getProcessedBy(), r.getRejectionReason());
    }
}
