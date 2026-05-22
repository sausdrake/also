package com.example.autoauction.diagnostic.infrastructure.persistence;

import com.example.autoauction.diagnostic.domain.InspectionGroup;
import com.example.autoauction.diagnostic.domain.InspectionItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class InspectionGroupMapper {

    private final InspectionItemMapper itemMapper;

    public JpaInspectionGroupEntity toEntity(InspectionGroup domain) {
        if (domain == null) return null;

        JpaInspectionGroupEntity entity = new JpaInspectionGroupEntity();
        entity.setId(domain.getId());
        entity.setTemplateId(domain.getTemplateId());
        entity.setName(domain.getName());
        entity.setSortOrder(domain.getSortOrder());
        entity.setCreatedAt(domain.getCreatedAt());
        return entity;
    }

    public InspectionGroup toDomain(JpaInspectionGroupEntity entity) {
        if (entity == null) return null;

        InspectionGroup domain = new InspectionGroup();
        domain.setId(entity.getId());
        domain.setTemplateId(entity.getTemplateId());
        domain.setName(entity.getName());
        domain.setSortOrder(entity.getSortOrder());
        domain.setCreatedAt(entity.getCreatedAt());
        return domain;
    }

    public InspectionGroup toDomainWithItems(JpaInspectionGroupEntity entity) {
        InspectionGroup domain = toDomain(entity);
        // items будут заполнены отдельно
        return domain;
    }
}