package com.example.autoauction.payment.infrastructure.persistence;

import com.example.autoauction.payment.domain.ReconciliationCase;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationCaseMapper {

    public JpaReconciliationCaseEntity toEntity(ReconciliationCase domain) {
        if (domain == null) return null;
        return new JpaReconciliationCaseEntity(
                domain.getId(),
                domain.getPaymentId(),
                domain.getType(),
                domain.getStatus(),
                domain.getOpenedAt(),
                domain.getResolvedByAdminId(),
                domain.getResolutionNote(),
                domain.getResolvedAt(),
                domain.getVersion());
    }

    public ReconciliationCase toDomain(JpaReconciliationCaseEntity entity) {
        if (entity == null) return null;
        return new ReconciliationCase(
                entity.getId(),
                entity.getPaymentId(),
                entity.getType(),
                entity.getStatus(),
                entity.getOpenedAt(),
                entity.getResolvedByAdminId(),
                entity.getResolutionNote(),
                entity.getResolvedAt(),
                entity.getVersion());
    }
}
