package com.example.autoauction.policy.web.dto;

import com.example.autoauction.policy.domain.PolicyVersion;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Slice 1: compact history-list row for a PolicyVersion.
 */
public record PolicyVersionSummaryResponse(
        Long id,
        Long versionNumber,
        BigDecimal commissionRate,
        boolean published,
        OffsetDateTime createdAt
) {
    public static PolicyVersionSummaryResponse fromDomain(PolicyVersion v) {
        return new PolicyVersionSummaryResponse(
                v.getId(), v.getVersionNumber(), v.getCommissionRate(), v.isPublished(), v.getCreatedAt());
    }
}
