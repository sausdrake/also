package com.example.autoauction.settlement.infrastructure.persistence;

import com.example.autoauction.settlement.domain.Settlement;
import org.springframework.stereotype.Component;

@Component
public class SettlementMapper {

    public JpaSettlementEntity toEntity(Settlement domain) {
        if (domain == null) return null;
        return new JpaSettlementEntity(
                domain.getId(),
                domain.getAuctionId(),
                domain.getSellerId(),
                domain.getExpectedBuyerPaidAmount(),
                domain.getExpectedSellerAmount(),
                domain.getExpectedCommission(),
                domain.getStatus(),
                domain.getAttestedPayoutAmount(),
                domain.getRecordedByAdminId(),
                domain.getRecordedAt(),
                domain.getCreatedAt());
    }

    public Settlement toDomain(JpaSettlementEntity entity) {
        if (entity == null) return null;
        return new Settlement(
                entity.getId(),
                entity.getAuctionId(),
                entity.getSellerId(),
                entity.getExpectedBuyerPaidAmount(),
                entity.getExpectedSellerAmount(),
                entity.getExpectedCommission(),
                entity.getStatus(),
                entity.getAttestedPayoutAmount(),
                entity.getRecordedByAdminId(),
                entity.getRecordedAt(),
                entity.getCreatedAt());
    }
}
