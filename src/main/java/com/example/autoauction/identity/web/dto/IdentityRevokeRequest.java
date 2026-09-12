package com.example.autoauction.identity.web.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code reasonText} is mandatory only when {@code reasonCode = OTHER} - enforced in the service. */
public record IdentityRevokeRequest(
        @NotBlank(message = "reasonCode обязателен")
        String reasonCode,

        String reasonText
) {
}
