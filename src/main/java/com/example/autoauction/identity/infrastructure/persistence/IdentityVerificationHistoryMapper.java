package com.example.autoauction.identity.infrastructure.persistence;

import com.example.autoauction.identity.domain.IdentityVerificationHistory;
import org.springframework.stereotype.Component;

@Component
public class IdentityVerificationHistoryMapper {

    public JpaIdentityVerificationHistoryEntity toEntity(IdentityVerificationHistory domain) {
        if (domain == null) return null;
        return new JpaIdentityVerificationHistoryEntity(
                domain.getId(),
                domain.getIdentityVerificationId(),
                domain.getEventType(),
                domain.getCanonicalDocumentNumber(),
                domain.getDocumentReferenceId(),
                domain.getActorAdminId(),
                domain.getRevocationReasonCode(),
                domain.getReasonText(),
                domain.getOccurredAt());
    }

    public IdentityVerificationHistory toDomain(JpaIdentityVerificationHistoryEntity entity) {
        if (entity == null) return null;
        return new IdentityVerificationHistory(
                entity.getId(),
                entity.getIdentityVerificationId(),
                entity.getEventType(),
                entity.getCanonicalDocumentNumber(),
                entity.getDocumentReferenceId(),
                entity.getActorAdminId(),
                entity.getRevocationReasonCode(),
                entity.getReasonText(),
                entity.getOccurredAt());
    }
}
