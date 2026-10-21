package com.example.autoauction.identity.web.dto;

import com.example.autoauction.identity.domain.IdentityVerification;

import java.time.OffsetDateTime;

/**
 * Shared by both the self-service "get own status" endpoint and the admin "inspect" endpoint -
 * exposing {@code userId} to the account owner themselves is trivially safe (it is their own
 * id), and the admin view needs it anyway.
 */
public record IdentityStatusResponse(
        Long id,
        Long userId,
        String status,
        String issuingCountry,
        String documentType,
        String canonicalDocumentNumber,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        // MVP-004: false only for submissions made before the live selfie became mandatory
        boolean selfieProvided,
        // KYC-CAPTURE-01: true when the back side of an ID card / driver licence was photographed
        boolean documentBackProvided
) {
    public static IdentityStatusResponse fromDomain(IdentityVerification iv) {
        return new IdentityStatusResponse(
                iv.getId(),
                iv.getUserId(),
                iv.getStatus() == null ? null : iv.getStatus().name(),
                iv.getIssuingCountry(),
                iv.getDocumentType() == null ? null : iv.getDocumentType().name(),
                iv.getCanonicalDocumentNumber(),
                iv.getCreatedAt(),
                iv.getUpdatedAt(),
                iv.getCurrentSelfieDocumentId() != null,
                iv.getCurrentDocumentBackId() != null);
    }
}
