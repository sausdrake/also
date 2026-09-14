package com.example.autoauction.policy.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

/**
 * Policy Administration Decision 6 (review-to-publish freshness): the complete publishable
 * configuration revision the checker observed immediately before publishing. The publishing
 * admin's identity comes from the authenticated principal ({@code @CurrentUser}), never from
 * this body.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PublishPolicyRequest(
        @NotNull(message = "observedConfigurationRevision обязателен")
        Long observedConfigurationRevision
) {
}
