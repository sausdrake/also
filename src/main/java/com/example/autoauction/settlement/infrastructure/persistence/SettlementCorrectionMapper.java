package com.example.autoauction.settlement.infrastructure.persistence;

import com.example.autoauction.settlement.domain.SettlementCorrection;
import org.springframework.stereotype.Component;

@Component
public class SettlementCorrectionMapper {

    public JpaSettlementCorrectionEntity toEntity(SettlementCorrection domain) {
        if (domain == null) return null;
        return new JpaSettlementCorrectionEntity(
                domain.getId(),
                domain.getSettlementId(),
                domain.getCorrectionType(),
                domain.getCorrectedAmount(),
                domain.getCorrectedSellerId(),
                domain.getReason(),
                domain.getCorrectedByAdminId(),
                domain.getCorrectedAt(),
                domain.getIdempotencyKey());
    }

    public SettlementCorrection toDomain(JpaSettlementCorrectionEntity entity) {
        if (entity == null) return null;
        return new SettlementCorrection(
                entity.getId(),
                entity.getSettlementId(),
                entity.getCorrectionType(),
                entity.getCorrectedAmount(),
                entity.getCorrectedSellerId(),
                entity.getReason(),
                entity.getCorrectedByAdminId(),
                entity.getCorrectedAt(),
                entity.getIdempotencyKey());
    }
}
