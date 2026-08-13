package com.example.autoauction.common.media.infrastructure.persistence;

import com.example.autoauction.common.media.domain.MediaAsset;
import org.springframework.stereotype.Component;

@Component
public class MediaAssetMapper {

    public JpaMediaAssetEntity toEntity(MediaAsset domain) {
        JpaMediaAssetEntity entity = new JpaMediaAssetEntity(domain.getStorageKey(), domain.getUploaderId());
        entity.setId(domain.getId());
        if (domain.getCreatedAt() != null) {
            entity.setCreatedAt(domain.getCreatedAt());
        }
        return entity;
    }

    public MediaAsset toDomain(JpaMediaAssetEntity entity) {
        return new MediaAsset(entity.getId(), entity.getStorageKey(), entity.getUploaderId(), entity.getCreatedAt());
    }
}
