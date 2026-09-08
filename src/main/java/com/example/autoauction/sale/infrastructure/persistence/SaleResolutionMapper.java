package com.example.autoauction.sale.infrastructure.persistence;

import com.example.autoauction.sale.domain.SaleResolution;
import org.springframework.stereotype.Component;

@Component
public class SaleResolutionMapper {

    public JpaSaleResolutionEntity toEntity(SaleResolution domain) {
        if (domain == null) return null;
        JpaSaleResolutionEntity entity = new JpaSaleResolutionEntity();
        entity.setId(domain.getId());
        entity.setAuctionId(domain.getAuctionId());
        entity.setResult(domain.getResult());
        entity.setReason(domain.getReason());
        entity.setResolvedBy(domain.getResolvedBy());
        entity.setResolvedAt(domain.getResolvedAt());
        entity.setPaymentEvidenceId(domain.getPaymentEvidenceId());
        entity.setHandoverEvidenceId(domain.getHandoverEvidenceId());
        entity.setFinancialResolutionStatus(domain.getFinancialResolutionStatus());
        entity.setCreatedAt(domain.getCreatedAt());
        return entity;
    }

    public SaleResolution toDomain(JpaSaleResolutionEntity entity) {
        if (entity == null) return null;
        return new SaleResolution(entity.getId(), entity.getAuctionId(), entity.getResult(), entity.getReason(),
                entity.getResolvedBy(), entity.getResolvedAt(), entity.getPaymentEvidenceId(),
                entity.getHandoverEvidenceId(), entity.getFinancialResolutionStatus(), entity.getCreatedAt());
    }
}
