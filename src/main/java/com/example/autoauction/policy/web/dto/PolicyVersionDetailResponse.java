package com.example.autoauction.policy.web.dto;

import com.example.autoauction.policy.domain.PolicyVersion;
import com.example.autoauction.policy.domain.PolicyVersionViolation;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Slice 1: full admin-facing view of one PolicyVersion (draft or published) plus its violation
 * configuration. Every field is server-owned; none is ever accepted from a request body.
 */
public record PolicyVersionDetailResponse(
        Long id,
        Long versionNumber,
        BigDecimal commissionRate,
        boolean published,
        Long createdBy,
        OffsetDateTime createdAt,
        Long publishedBy,
        OffsetDateTime publishedAt,
        Long basedOnPublishedVersionId,
        Long version,
        Long configurationRevision,
        List<ViolationView> violations
) {
    public static PolicyVersionDetailResponse fromDomain(PolicyVersion v,
                                                         List<PolicyVersionViolation> violations,
                                                         Map<Long, String> codeByViolationDefinitionId) {
        List<ViolationView> views = violations.stream()
                .map(pvv -> ViolationView.fromDomain(
                        pvv, codeByViolationDefinitionId.getOrDefault(pvv.getViolationDefinitionId(), null)))
                .toList();
        return new PolicyVersionDetailResponse(
                v.getId(), v.getVersionNumber(), v.getCommissionRate(), v.isPublished(),
                v.getCreatedBy(), v.getCreatedAt(), v.getPublishedBy(), v.getPublishedAt(),
                v.getBasedOnPublishedVersionId(), v.getVersion(), v.getConfigurationRevision(), views);
    }
}
