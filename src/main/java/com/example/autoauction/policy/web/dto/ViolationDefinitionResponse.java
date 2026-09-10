package com.example.autoauction.policy.web.dto;

import com.example.autoauction.policy.domain.ViolationDefinition;

/**
 * Slice 1: read-only view of one stable violation-code catalogue entry.
 */
public record ViolationDefinitionResponse(
        Long id,
        String code,
        String description
) {
    public static ViolationDefinitionResponse fromDomain(ViolationDefinition d) {
        return new ViolationDefinitionResponse(d.getId(), d.getCode(), d.getDescription());
    }
}
