package com.example.autoauction.identity.web.dto;

import com.example.autoauction.identity.domain.IdentityVerificationHistory;

import java.time.OffsetDateTime;

/**
 * Phase 3 §17 visibility matrix: the owner sees their own history but NEVER the reviewer's
 * identity; the admin sees everything. {@link #fromDomainForOwner} always nulls out
 * {@code reviewerAdminId} regardless of what the underlying row holds - {@link #fromDomainForAdmin}
 * exposes it.
 */
public record IdentityHistoryEventResponse(
        Long id,
        String eventType,
        String canonicalDocumentNumber,
        Long reviewerAdminId,
        String revocationReasonCode,
        String reasonText,
        OffsetDateTime occurredAt
) {
    public static IdentityHistoryEventResponse fromDomainForOwner(IdentityVerificationHistory h) {
        return new IdentityHistoryEventResponse(
                h.getId(),
                h.getEventType() == null ? null : h.getEventType().name(),
                h.getCanonicalDocumentNumber(),
                null,
                h.getRevocationReasonCode() == null ? null : h.getRevocationReasonCode().name(),
                h.getReasonText(),
                h.getOccurredAt());
    }

    public static IdentityHistoryEventResponse fromDomainForAdmin(IdentityVerificationHistory h) {
        return new IdentityHistoryEventResponse(
                h.getId(),
                h.getEventType() == null ? null : h.getEventType().name(),
                h.getCanonicalDocumentNumber(),
                h.getActorAdminId(),
                h.getRevocationReasonCode() == null ? null : h.getRevocationReasonCode().name(),
                h.getReasonText(),
                h.getOccurredAt());
    }
}
