package com.example.autoauction.identity.web.dto;

import jakarta.validation.constraints.NotBlank;

public record IdentityRejectRequest(
        @NotBlank(message = "reason обязателен")
        String reason
) {
}
