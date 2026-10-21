package com.example.autoauction.identity.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * KYC-CAPTURE-01: a slot is requested for one camera capture - its side (FRONT, BACK, DATA_PAGE, SELFIE) and,
 * for a document side, its document type. The owner is always the authenticated user, never a body field.
 */
public record IdentityUploadUrlRequest(
        @NotBlank(message = "contentType обязателен")
        String contentType,

        @NotBlank(message = "side обязателен")
        String side,

        /* required for FRONT / BACK / DATA_PAGE, absent for SELFIE */
        String documentType
) {
}
