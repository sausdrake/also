package com.example.autoauction.diagnostic.web.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * X3-E: audit summary returned after a seller-violation resolution - never exposes the
 * internal domain entities directly.
 */
public record SellerViolationResolutionResponse(
        Long sellerDiagnosticCommitmentId,
        List<Long> violationDefinitionIds,
        BigDecimal rawPenaltyAmount,
        BigDecimal appliedPenaltyAmount,
        BigDecimal refundAmount,
        OffsetDateTime resolvedAt
) {
}
