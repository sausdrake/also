package com.example.autoauction.sale.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/**
 * Slice 1B: admin-supplied payment-deadline override/extension. Deliberately does NOT contain
 * an admin id - the actor is always taken from the authenticated principal (locked rule).
 * {@code newDeadline} may be in the past (no "must be future" rule - locked rule); {@code reason}
 * is mandatory for every change.
 */
public record PaymentDeadlineExtendRequest(
        @NotNull(message = "newDeadline обязателен")
        OffsetDateTime newDeadline,

        @NotBlank(message = "reason обязателен")
        String reason
) {
}
