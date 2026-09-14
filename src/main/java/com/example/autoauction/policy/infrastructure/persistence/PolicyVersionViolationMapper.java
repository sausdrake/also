package com.example.autoauction.policy.infrastructure.persistence;

import com.example.autoauction.policy.domain.PolicyVersionViolation;
import org.springframework.stereotype.Component;

@Component
public class PolicyVersionViolationMapper {

    public JpaPolicyVersionViolationEntity toEntity(PolicyVersionViolation domain) {
        if (domain == null) return null;
        JpaPolicyVersionViolationEntity entity = new JpaPolicyVersionViolationEntity(
                domain.getPolicyVersionId(), domain.getViolationDefinitionId(),
                domain.getPenaltyType(), domain.getPenaltyValue(), domain.getCreatedAt());
        entity.setId(domain.getId());
        entity.setVersion(domain.getVersion());
        return entity;
    }

    public PolicyVersionViolation toDomain(JpaPolicyVersionViolationEntity entity) {
        if (entity == null) return null;
        return new PolicyVersionViolation(entity.getId(), entity.getPolicyVersionId(), entity.getViolationDefinitionId(),
                entity.getPenaltyType(), entity.getPenaltyValue(), entity.getCreatedAt(), entity.getVersion());
    }
}
