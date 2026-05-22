package com.example.autoauction.diagnostic.infrastructure.persistence;

import com.example.autoauction.diagnostic.domain.InspectionGroup;
import com.example.autoauction.diagnostic.domain.InspectionItem;
import com.example.autoauction.diagnostic.domain.InspectionTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class InspectionTemplateMapper {

    private final InspectionGroupMapper groupMapper;

    public JpaInspectionTemplateEntity toEntity(InspectionTemplate domain) {
        if (domain == null) return null;

        JpaInspectionTemplateEntity entity = new JpaInspectionTemplateEntity();
        entity.setId(domain.getId());
        entity.setName(domain.getName());
        entity.setVehicleType(domain.getVehicleType());
        entity.setDescription(domain.getDescription());
        entity.setActive(domain.isActive());
        entity.setCreatedBy(domain.getCreatedBy());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    public InspectionTemplate toDomain(JpaInspectionTemplateEntity entity) {
        if (entity == null) return null;

        InspectionTemplate domain = new InspectionTemplate();
        domain.setId(entity.getId());
        domain.setName(entity.getName());
        domain.setVehicleType(entity.getVehicleType());
        domain.setDescription(entity.getDescription());
        domain.setActive(entity.isActive());
        domain.setCreatedBy(entity.getCreatedBy());
        domain.setCreatedAt(entity.getCreatedAt());
        domain.setUpdatedAt(entity.getUpdatedAt());
        return domain;
    }

    public InspectionTemplate toDomainWithGroups(JpaInspectionTemplateEntity entity,
                                                 java.util.List<JpaInspectionGroupEntity> groupEntities) {
        InspectionTemplate domain = toDomain(entity);
        if (groupEntities != null) {
            domain.setGroups(groupEntities.stream()
                    .map(groupMapper::toDomainWithItems)
                    .collect(Collectors.toList()));
        }
        return domain;
    }
}