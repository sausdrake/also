package com.example.autoauction.policy.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Slice 1: the only field a client supplies when creating a new draft PolicyVersion. The new
 * draft's violation configuration is copied forward from the current published version by the
 * server. {@code id}, {@code versionNumber}, {@code published}, {@code createdBy},
 * {@code createdAt}, {@code effectiveFrom} are all server-owned - any such field in the body is
 * silently ignored ({@code @JsonIgnoreProperties(ignoreUnknown = true)}), never bound.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreatePolicyDraftRequest(
        @NotNull(message = "commissionRate обязателен")
        @DecimalMin(value = "0", message = "commissionRate не может быть отрицательным")
        @DecimalMax(value = "1", inclusive = false, message = "commissionRate должен быть меньше 1 (100%)")
        BigDecimal commissionRate
) {
}
