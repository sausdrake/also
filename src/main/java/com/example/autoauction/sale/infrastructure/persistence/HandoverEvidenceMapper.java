package com.example.autoauction.sale.infrastructure.persistence;

import com.example.autoauction.sale.domain.HandoverEvidence;
import org.springframework.stereotype.Component;

@Component
public class HandoverEvidenceMapper {

    public JpaHandoverEvidenceEntity toEntity(HandoverEvidence domain) {
        if (domain == null) return null;
        JpaHandoverEvidenceEntity entity = new JpaHandoverEvidenceEntity();
        entity.setId(domain.getId());
        entity.setAuctionId(domain.getAuctionId());
        entity.setVehicleId(domain.getVehicleId());
        entity.setBuyerId(domain.getBuyerId());
        entity.setConfirmedByAdminId(domain.getConfirmedByAdminId());
        entity.setConfirmedAt(domain.getConfirmedAt());
        entity.setCreatedAt(domain.getCreatedAt());
        return entity;
    }

    public HandoverEvidence toDomain(JpaHandoverEvidenceEntity entity) {
        if (entity == null) return null;
        return new HandoverEvidence(entity.getId(), entity.getAuctionId(), entity.getVehicleId(),
                entity.getBuyerId(), entity.getConfirmedByAdminId(), entity.getConfirmedAt(), entity.getCreatedAt());
    }
}
