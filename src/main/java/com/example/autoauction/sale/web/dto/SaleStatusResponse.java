package com.example.autoauction.sale.web.dto;

import java.time.OffsetDateTime;

/**
 * Slice (F5/sale-UI): read-only admin assembly of an auction's current sale-workflow state -
 * the payment deadline/overdue fact plus whichever of PaymentEvidence/HandoverEvidence/
 * SaleResolution already exist for this auction. Pure exposure of already-existing facts (each
 * nested response is the same DTO the write endpoints already return) - introduces no new
 * business logic, no new query beyond the already-existing findByAuctionId lookups.
 */
public record SaleStatusResponse(
        Long auctionId,
        String auctionStatus,
        OffsetDateTime paymentDeadline,
        boolean overdue,
        PaymentEvidenceResponse paymentEvidence,
        HandoverEvidenceResponse handoverEvidence,
        SaleResolutionResponse saleResolution
) {
}
