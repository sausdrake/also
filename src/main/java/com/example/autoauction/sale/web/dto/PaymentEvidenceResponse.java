package com.example.autoauction.sale.web.dto;

import com.example.autoauction.sale.domain.PaymentEvidence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Slice 1: admin-facing view of an immutable {@link PaymentEvidence} fact. Never exposes the
 * JPA entity. Ids shown are acceptable - every endpoint here is ADMIN-only.
 */
public record PaymentEvidenceResponse(
        Long id,
        Long auctionId,
        Long buyerId,
        BigDecimal amount,
        Long receivedByAdminId,
        OffsetDateTime receivedAt
) {
    public static PaymentEvidenceResponse fromDomain(PaymentEvidence e) {
        return new PaymentEvidenceResponse(
                e.getId(), e.getAuctionId(), e.getBuyerId(), e.getAmount(),
                e.getReceivedByAdminId(), e.getReceivedAt());
    }
}
