package com.example.autoauction.sale.web.dto;

import com.example.autoauction.settlement.domain.Settlement;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Step 2C: admin-facing view of a {@link Settlement} - the expected economic fields are the
 * sale's fixed, immutable economics; {@code attestedPayoutAmount} is the separately-attested
 * physical-handover fact, which a reader must compare against {@code expectedSellerAmount}
 * itself to see any discrepancy (none is hidden or pre-reconciled here).
 */
public record SettlementResponse(
        Long id,
        Long auctionId,
        Long sellerId,
        BigDecimal expectedBuyerPaidAmount,
        BigDecimal expectedSellerAmount,
        BigDecimal expectedCommission,
        String status,
        BigDecimal attestedPayoutAmount,
        Long recordedByAdminId,
        OffsetDateTime recordedAt,
        OffsetDateTime createdAt
) {
    public static SettlementResponse fromDomain(Settlement s) {
        return new SettlementResponse(
                s.getId(),
                s.getAuctionId(),
                s.getSellerId(),
                s.getExpectedBuyerPaidAmount(),
                s.getExpectedSellerAmount(),
                s.getExpectedCommission(),
                s.getStatus() == null ? null : s.getStatus().name(),
                s.getAttestedPayoutAmount(),
                s.getRecordedByAdminId(),
                s.getRecordedAt(),
                s.getCreatedAt());
    }
}
