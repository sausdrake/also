package com.example.autoauction.identity.infrastructure.persistence;

import com.example.autoauction.identity.domain.IdentityVerification;
import org.springframework.stereotype.Component;

@Component
public class IdentityVerificationMapper {

    public JpaIdentityVerificationEntity toEntity(IdentityVerification domain) {
        if (domain == null) return null;
        JpaIdentityVerificationEntity entity = new JpaIdentityVerificationEntity();
        entity.setId(domain.getId());
        entity.setUserId(domain.getUserId());
        entity.setStatus(domain.getStatus());
        entity.setIssuingCountry(domain.getIssuingCountry());
        entity.setDocumentType(domain.getDocumentType());
        entity.setCanonicalDocumentNumber(domain.getCanonicalDocumentNumber());
        entity.setCurrentDocumentId(domain.getCurrentDocumentId());
        entity.setCurrentSelfieDocumentId(domain.getCurrentSelfieDocumentId());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        entity.setVersion(domain.getVersion());
        return entity;
    }

    public IdentityVerification toDomain(JpaIdentityVerificationEntity entity) {
        if (entity == null) return null;
        IdentityVerification domain = new IdentityVerification(
                entity.getId(),
                entity.getUserId(),
                entity.getStatus(),
                entity.getIssuingCountry(),
                entity.getDocumentType(),
                entity.getCanonicalDocumentNumber(),
                entity.getCurrentDocumentId(),
                entity.getCurrentSelfieDocumentId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion());
        return domain;
    }
}
