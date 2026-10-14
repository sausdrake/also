package com.example.autoauction.identity.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Deliberately does NOT contain a userId field - the actor/subject is always taken from the
 * authenticated principal (Phase 3.1 §4/Phase 4 §6). {@code rawDocumentNumber} exists only
 * transiently for the duration of this request - it is canonicalized immediately and never
 * persisted (Phase 3.1 §5).
 */
public record IdentitySubmissionRequest(
        @NotBlank(message = "issuingCountry обязателен")
        String issuingCountry,

        @NotBlank(message = "documentType обязателен")
        String documentType,

        @NotBlank(message = "rawDocumentNumber обязателен")
        String rawDocumentNumber,

        @NotBlank(message = "documentStorageKey обязателен")
        String documentStorageKey,

        // MVP-004: storage key of the live selfie captured through the platform camera
        @NotBlank(message = "selfieStorageKey обязателен")
        String selfieStorageKey
) {
}
