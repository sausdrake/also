package com.example.autoauction.identity.infrastructure.persistence;

import com.example.autoauction.identity.domain.IdentityDocument;
import org.springframework.stereotype.Component;

@Component
public class IdentityDocumentMapper {

    public JpaIdentityDocumentEntity toEntity(IdentityDocument domain) {
        if (domain == null) return null;
        return new JpaIdentityDocumentEntity(
                domain.getId(),
                domain.getStorageKey(),
                domain.getUploaderUserId(),
                domain.getMimeType(),
                domain.getSizeBytes(),
                domain.getCreatedAt(),
                domain.getCaptureSide(),
                domain.getDocumentType(),
                domain.getConsumedAt());
    }

    public IdentityDocument toDomain(JpaIdentityDocumentEntity entity) {
        if (entity == null) return null;
        return new IdentityDocument(
                entity.getId(),
                entity.getStorageKey(),
                entity.getUploaderUserId(),
                entity.getMimeType(),
                entity.getSizeBytes(),
                entity.getCreatedAt(),
                entity.getCaptureSide(),
                entity.getDocumentType(),
                entity.getConsumedAt());
    }
}
