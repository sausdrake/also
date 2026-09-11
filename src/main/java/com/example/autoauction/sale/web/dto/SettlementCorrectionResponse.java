package com.example.autoauction.sale.web.dto;

import com.example.autoauction.settlement.domain.SettlementCorrection;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Step 2C: admin-facing view of a recorded {@link SettlementCorrection}. */
public record SettlementCorrectionResponse(
        Long id,
        Long settlementId,
        String correctionType,
        BigDecimal correctedAmount,
        Long correctedSellerId,
        String reason,
        Long correctedByAdminId,
        OffsetDateTime correctedAt
) {
    public static SettlementCorrectionResponse fromDomain(SettlementCorrection c) {
        return new SettlementCorrectionResponse(
                c.getId(),
                c.getSettlementId(),
                c.getCorrectionType() == null ? null : c.getCorrectionType().name(),
                c.getCorrectedAmount(),
                c.getCorrectedSellerId(),
                c.getReason(),
                c.getCorrectedByAdminId(),
                c.getCorrectedAt());
    }
}
