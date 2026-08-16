package com.example.autoauction.common.web.dto;

import com.example.autoauction.common.domain.FailedOperation;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "Сбойная финансовая операция и статус её восстановления")
public record FailedOperationResponse(
        Long id,
        String operationType,
        Long userId,
        Long auctionId,
        String status,
        int retryCount,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime nextRetryAt,
        OffsetDateTime resolvedAt
) {
    public static FailedOperationResponse fromDomain(FailedOperation op) {
        return new FailedOperationResponse(
                op.getId(),
                op.getOperationType(),
                op.getUserId(),
                op.getAuctionId(),
                op.getStatus(),
                op.getRetryCount(),
                op.getErrorMessage(),
                op.getCreatedAt(),
                op.getUpdatedAt(),
                op.getNextRetryAt(),
                op.getResolvedAt()
        );
    }
}
