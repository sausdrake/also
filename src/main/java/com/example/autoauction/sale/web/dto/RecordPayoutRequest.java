package com.example.autoauction.sale.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Step 2C: the only value the client supplies when recording a seller payout attestation - the
 * amount the admin states was physically handed to the seller. No seller id, no commission, no
 * expected amount, no wallet/account information, and no physical-cash-verification field exist
 * here - seller identity and the sale's economics are both already fixed on the Settlement this
 * request targets.
 */
public record RecordPayoutRequest(
        @NotNull(message = "attestedAmount обязателен")
        @Positive(message = "attestedAmount должен быть больше 0")
        BigDecimal attestedAmount
) {
}
