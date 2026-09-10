package com.example.autoauction.sale.web.dto;

import com.example.autoauction.sale.domain.SaleResolution;

import java.time.OffsetDateTime;

/**
 * Slice 1: admin-facing view of the authoritative {@link SaleResolution} for an auction -
 * returned by both {@code /complete} (result = SUCCESS) and {@code /fail} (result = one of
 * B / BUYER_FAULT / PLATFORM_FAULT / C). {@code faultParty} is the server-derived value, shown
 * for audit; it is never an input.
 */
public record SaleResolutionResponse(
        Long id,
        Long auctionId,
        String result,
        String faultParty,
        String reason,
        Long resolvedBy,
        OffsetDateTime resolvedAt,
        Long paymentEvidenceId,
        Long handoverEvidenceId,
        String financialResolutionStatus
) {
    public static SaleResolutionResponse fromDomain(SaleResolution r) {
        return new SaleResolutionResponse(
                r.getId(),
                r.getAuctionId(),
                r.getResult() == null ? null : r.getResult().name(),
                r.getFaultParty() == null ? null : r.getFaultParty().name(),
                r.getReason(),
                r.getResolvedBy(),
                r.getResolvedAt(),
                r.getPaymentEvidenceId(),
                r.getHandoverEvidenceId(),
                r.getFinancialResolutionStatus() == null ? null : r.getFinancialResolutionStatus().name());
    }
}
