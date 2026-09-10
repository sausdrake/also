package com.example.autoauction.sale.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Slice 1: the only value the client supplies when recording payment - the cash amount the
 * admin physically received. It must equal the auction's {@code winningBid} exactly (validated
 * server-side by {@code PaymentEvidenceService}). Buyer identity, admin identity and auction
 * state are all derived server-side and must never appear here.
 */
public record PaymentRequest(
        @NotNull(message = "amount обязателен")
        @Positive(message = "amount должен быть больше 0")
        BigDecimal amount
) {
}
