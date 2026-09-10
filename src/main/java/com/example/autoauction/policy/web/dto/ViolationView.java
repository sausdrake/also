package com.example.autoauction.policy.web.dto;

import com.example.autoauction.policy.domain.PenaltyType;
import com.example.autoauction.policy.domain.PolicyVersionViolation;

import java.math.BigDecimal;

/**
 * Slice 1: one configured violation penalty within a PolicyVersion, joined with its stable
 * {@code code} for readability. Never exposes the JPA entity.
 */
public record ViolationView(
        Long violationDefinitionId,
        String code,
        PenaltyType penaltyType,
        BigDecimal penaltyValue
) {
    public static ViolationView fromDomain(PolicyVersionViolation v, String code) {
        return new ViolationView(v.getViolationDefinitionId(), code, v.getPenaltyType(), v.getPenaltyValue());
    }
}
