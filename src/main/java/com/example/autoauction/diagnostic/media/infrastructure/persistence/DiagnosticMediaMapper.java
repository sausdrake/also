package com.example.autoauction.diagnostic.media.infrastructure.persistence;

import com.example.autoauction.diagnostic.media.domain.DiagnosticMedia;
import org.springframework.stereotype.Component;

@Component
public class DiagnosticMediaMapper {

    public JpaDiagnosticMediaEntity toEntity(DiagnosticMedia domain) {
        if (domain == null) return null;

        JpaDiagnosticMediaEntity entity = new JpaDiagnosticMediaEntity();
        entity.setId(domain.getId());
        entity.setDiagnosticRequestId(domain.getDiagnosticRequestId());
        entity.setInspectionItemId(domain.getInspectionItemId());
        entity.setUploaderId(domain.getUploaderId());
        entity.setStorageKey(domain.getStorageKey());
        entity.setMediaType(domain.getMediaType());
        entity.setMimeType(domain.getMimeType());
        entity.setSizeBytes(domain.getSizeBytes());
        entity.setSortOrder(domain.getSortOrder());
        entity.setCaption(domain.getCaption());
        entity.setUploadStatus(domain.getUploadStatus());
        entity.setProcessingStatus(domain.getProcessingStatus());
        entity.setDurationSeconds(domain.getDurationSeconds());
        entity.setWidth(domain.getWidth());
        entity.setHeight(domain.getHeight());
        entity.setThumbnailStorageKey(domain.getThumbnailStorageKey());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    public DiagnosticMedia toDomain(JpaDiagnosticMediaEntity entity) {
        if (entity == null) return null;

        DiagnosticMedia domain = new DiagnosticMedia();
        domain.setId(entity.getId());
        domain.setDiagnosticRequestId(entity.getDiagnosticRequestId());
        domain.setInspectionItemId(entity.getInspectionItemId());
        domain.setUploaderId(entity.getUploaderId());
        domain.setStorageKey(entity.getStorageKey());
        domain.setMediaType(entity.getMediaType());
        domain.setMimeType(entity.getMimeType());
        domain.setSizeBytes(entity.getSizeBytes());
        domain.setSortOrder(entity.getSortOrder());
        domain.setCaption(entity.getCaption());
        domain.setUploadStatus(entity.getUploadStatus());
        domain.setProcessingStatus(entity.getProcessingStatus());
        domain.setDurationSeconds(entity.getDurationSeconds());
        domain.setWidth(entity.getWidth());
        domain.setHeight(entity.getHeight());
        domain.setThumbnailStorageKey(entity.getThumbnailStorageKey());
        domain.setCreatedAt(entity.getCreatedAt());
        domain.setUpdatedAt(entity.getUpdatedAt());
        return domain;
    }
}
