package com.example.autoauction.sale.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Step 2C: request body for recording a Settlement correction. Exactly one of
 * {@code correctedAmount}/{@code correctedSellerId} must be populated, matching
 * {@code correctionType} - enforced by {@code SettlementCorrection}'s own constructor, not
 * duplicated here. {@code reason} and {@code idempotencyKey} are mandatory for every correction
 * type, including {@code PAYOUT_INVALIDATED}.
 */
public record SettlementCorrectionRequest(
        @NotBlank(message = "correctionType обязателен")
        String correctionType,

        BigDecimal correctedAmount,

        Long correctedSellerId,

        @NotBlank(message = "reason обязателен")
        String reason,

        @NotNull(message = "idempotencyKey обязателен")
        @NotBlank(message = "idempotencyKey обязателен")
        String idempotencyKey
) {
}
