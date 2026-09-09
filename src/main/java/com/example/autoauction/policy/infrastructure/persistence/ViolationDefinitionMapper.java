package com.example.autoauction.policy.infrastructure.persistence;

import com.example.autoauction.policy.domain.ViolationDefinition;
import org.springframework.stereotype.Component;

@Component
public class ViolationDefinitionMapper {

    public ViolationDefinition toDomain(JpaViolationDefinitionEntity entity) {
        if (entity == null) return null;
        return new ViolationDefinition(entity.getId(), entity.getCode(), entity.getDescription(), entity.getCreatedAt());
    }
}
