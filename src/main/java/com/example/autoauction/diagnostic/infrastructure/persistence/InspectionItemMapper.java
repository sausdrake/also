package com.example.autoauction.diagnostic.infrastructure.persistence;

import com.example.autoauction.diagnostic.domain.InspectionItem;
import org.springframework.stereotype.Component;

@Component
public class InspectionItemMapper {

    public JpaInspectionItemEntity toEntity(InspectionItem domain) {
        if (domain == null) return null;

        JpaInspectionItemEntity entity = new JpaInspectionItemEntity();
        entity.setId(domain.getId());
        entity.setGroupId(domain.getGroupId());
        entity.setTitle(domain.getTitle());
        entity.setDescription(domain.getDescription());
        entity.setHasPhoto(domain.isHasPhoto());
        entity.setHasVideo(domain.isHasVideo());
        entity.setHasRating(domain.isHasRating());
        entity.setRequired(domain.isRequired());
        entity.setSortOrder(domain.getSortOrder());
        entity.setCreatedAt(domain.getCreatedAt());
        return entity;
    }

    public InspectionItem toDomain(JpaInspectionItemEntity entity) {
        if (entity == null) return null;

        InspectionItem domain = new InspectionItem();
        domain.setId(entity.getId());
        domain.setGroupId(entity.getGroupId());
        domain.setTitle(entity.getTitle());
        domain.setDescription(entity.getDescription());
        domain.setHasPhoto(entity.isHasPhoto());
        domain.setHasVideo(entity.isHasVideo());
        domain.setHasRating(entity.isHasRating());
        domain.setRequired(entity.isRequired());
        domain.setSortOrder(entity.getSortOrder());
        domain.setCreatedAt(entity.getCreatedAt());
        return domain;
    }
}