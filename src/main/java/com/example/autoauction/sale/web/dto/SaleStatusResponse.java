package com.example.autoauction.sale.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Slice (F5/sale-UI): read-only admin assembly of an auction's current sale-workflow state -
 * the payment deadline/overdue fact plus whichever of PaymentEvidence/HandoverEvidence/
 * SaleResolution/Settlement already exist for this auction. Pure exposure of already-existing
 * facts (each nested response is the same DTO the write endpoints already return) - introduces
 * no new business logic, no new query beyond the already-existing findByAuctionId lookups.
 *
 * <p>{@code settlement} is null when no Settlement row exists yet for this auction (not yet
 * completed, or a legacy pre-Settlement SOLD auction) - never an error. {@code
 * settlementCorrections} is always a list, empty when {@code settlement} is null or has no
 * corrections recorded.
 */
public record SaleStatusResponse(
        Long auctionId,
        String auctionStatus,
        OffsetDateTime paymentDeadline,
        boolean overdue,
        PaymentEvidenceResponse paymentEvidence,
        HandoverEvidenceResponse handoverEvidence,
        SaleResolutionResponse saleResolution,
        SettlementResponse settlement,
        List<SettlementCorrectionResponse> settlementCorrections
) {
}
