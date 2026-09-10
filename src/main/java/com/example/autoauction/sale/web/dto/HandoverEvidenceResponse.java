package com.example.autoauction.sale.web.dto;

import com.example.autoauction.sale.domain.HandoverEvidence;

import java.time.OffsetDateTime;

/**
 * Slice 1: admin-facing view of an immutable {@link HandoverEvidence} fact.
 */
public record HandoverEvidenceResponse(
        Long id,
        Long auctionId,
        Long vehicleId,
        Long buyerId,
        Long confirmedByAdminId,
        OffsetDateTime confirmedAt
) {
    public static HandoverEvidenceResponse fromDomain(HandoverEvidence e) {
        return new HandoverEvidenceResponse(
                e.getId(), e.getAuctionId(), e.getVehicleId(), e.getBuyerId(),
                e.getConfirmedByAdminId(), e.getConfirmedAt());
    }
}
