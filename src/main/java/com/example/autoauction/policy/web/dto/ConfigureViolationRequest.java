package com.example.autoauction.policy.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Slice 1: penalty configuration for one violation on a draft PolicyVersion via
 * {@code PUT /drafts/{id}/violations/{violationDefinitionId}}. The {@code violationDefinitionId}
 * comes from the PATH, never from this body.
 *
 * <p>{@code penaltyType} is a raw string (not the {@link com.example.autoauction.policy.domain.PenaltyType}
 * enum) so an invalid value is parsed in the controller into an {@code IllegalArgumentException}
 * -&gt; HTTP 400, rather than a Jackson deserialization failure that the existing
 * GlobalExceptionHandler would map to 500. Accepted values: {@code PERCENTAGE}, {@code FIXED_AMOUNT}.
 *
 * <p>Value bounds are enforced by the domain ({@code PolicyVersionViolation}): PERCENTAGE in
 * [0, 100]; FIXED_AMOUNT &gt;= 0. The {@code @DecimalMin("0")} here is a cheap early reject only.
 * Any unexpected field in the body is silently ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfigureViolationRequest(
        @NotBlank(message = "penaltyType обязателен")
        String penaltyType,

        @NotNull(message = "penaltyValue обязателен")
        @DecimalMin(value = "0", message = "penaltyValue не может быть отрицательным")
        BigDecimal penaltyValue
) {
}
