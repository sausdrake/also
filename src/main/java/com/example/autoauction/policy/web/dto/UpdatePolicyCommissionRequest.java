package com.example.autoauction.policy.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Slice 1: the single mutable field on a draft PolicyVersion via {@code PATCH /drafts/{id}}.
 * Rejected (409) by the server if the target version is already published. Any server-owned
 * field in the body is silently ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdatePolicyCommissionRequest(
        @NotNull(message = "commissionRate обязателен")
        @DecimalMin(value = "0", message = "commissionRate не может быть отрицательным")
        @DecimalMax(value = "1", inclusive = false, message = "commissionRate должен быть меньше 1 (100%)")
        BigDecimal commissionRate
) {
}
