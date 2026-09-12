package com.example.autoauction.identity.web.dto;

import jakarta.validation.constraints.NotBlank;

public record IdentityUploadUrlRequest(
        @NotBlank(message = "contentType обязателен")
        String contentType
) {
}
