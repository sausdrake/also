package com.example.autoauction.sale.web.dto;

import com.example.autoauction.auction.domain.PaymentDeadlineHistory;

import java.time.OffsetDateTime;

/** Slice 1B: admin-facing view of one {@link PaymentDeadlineHistory} row. */
public record PaymentDeadlineHistoryResponse(
        Long id,
        Long auctionId,
        String changeType,
        OffsetDateTime previousDeadline,
        OffsetDateTime newDeadline,
        String reason,
        Long changedByAdminId,
        OffsetDateTime changedAt
) {
    public static PaymentDeadlineHistoryResponse fromDomain(PaymentDeadlineHistory h) {
        return new PaymentDeadlineHistoryResponse(
                h.getId(),
                h.getAuctionId(),
                h.getChangeType() == null ? null : h.getChangeType().name(),
                h.getPreviousDeadline(),
                h.getNewDeadline(),
                h.getReason(),
                h.getChangedByAdminId(),
                h.getChangedAt());
    }
}
