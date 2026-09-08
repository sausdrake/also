package com.example.autoauction.policy.infrastructure.persistence;

import com.example.autoauction.policy.domain.PolicyVersion;
import org.springframework.stereotype.Component;

@Component
public class PolicyVersionMapper {

    public JpaPolicyVersionEntity toEntity(PolicyVersion domain) {
        if (domain == null) return null;

        JpaPolicyVersionEntity entity = new JpaPolicyVersionEntity();
        entity.setId(domain.getId());
        entity.setVersionNumber(domain.getVersionNumber());
        entity.setCommissionRate(domain.getCommissionRate());
        entity.setPublished(domain.isPublished());
        entity.setEffectiveFrom(domain.getEffectiveFrom());
        entity.setCreatedBy(domain.getCreatedBy());
        entity.setCreatedAt(domain.getCreatedAt());
        return entity;
    }

    public PolicyVersion toDomain(JpaPolicyVersionEntity entity) {
        if (entity == null) return null;

        return new PolicyVersion(
                entity.getId(),
                entity.getVersionNumber(),
                entity.getCommissionRate(),
                entity.isPublished(),
                entity.getEffectiveFrom(),
                entity.getCreatedBy(),
                entity.getCreatedAt()
        );
    }
}
