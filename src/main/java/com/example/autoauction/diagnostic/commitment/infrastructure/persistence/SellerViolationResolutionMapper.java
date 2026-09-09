package com.example.autoauction.diagnostic.commitment.infrastructure.persistence;

import com.example.autoauction.diagnostic.commitment.domain.SellerViolationResolution;
import com.example.autoauction.diagnostic.commitment.domain.SellerViolationResolutionItem;
import org.springframework.stereotype.Component;

@Component
public class SellerViolationResolutionMapper {

    public JpaSellerViolationResolutionEntity toEntity(SellerViolationResolution domain) {
        if (domain == null) return null;
        JpaSellerViolationResolutionEntity entity = new JpaSellerViolationResolutionEntity(
                domain.getSellerDiagnosticCommitmentId(), domain.getTriggerSource(), domain.getAuctionId(),
                domain.getPolicyVersionId(), domain.getRawPenaltyAmount(), domain.getAppliedPenaltyAmount(),
                domain.getRefundAmount(), domain.getResolvedBy(), domain.getResolvedAt(), domain.getCreatedAt());
        entity.setId(domain.getId());
        return entity;
    }

    public SellerViolationResolution toDomain(JpaSellerViolationResolutionEntity entity) {
        if (entity == null) return null;
        return new SellerViolationResolution(entity.getId(), entity.getSellerDiagnosticCommitmentId(),
                entity.getTriggerSource(), entity.getAuctionId(), entity.getPolicyVersionId(),
                entity.getRawPenaltyAmount(), entity.getAppliedPenaltyAmount(), entity.getRefundAmount(),
                entity.getResolvedBy(), entity.getResolvedAt(), entity.getCreatedAt());
    }

    public JpaSellerViolationResolutionItemEntity toItemEntity(SellerViolationResolutionItem domain,
                                                                Long sellerViolationResolutionId) {
        if (domain == null) return null;
        JpaSellerViolationResolutionItemEntity entity = new JpaSellerViolationResolutionItemEntity(
                sellerViolationResolutionId, domain.getViolationDefinitionId(), domain.getConfiguredPenaltyType(),
                domain.getConfiguredPenaltyValue(), domain.getCalculatedPenaltyAmount(), domain.getCreatedAt());
        entity.setId(domain.getId());
        return entity;
    }

    public SellerViolationResolutionItem toItemDomain(JpaSellerViolationResolutionItemEntity entity) {
        if (entity == null) return null;
        return new SellerViolationResolutionItem(entity.getId(), entity.getSellerViolationResolutionId(),
                entity.getViolationDefinitionId(), entity.getConfiguredPenaltyType(), entity.getConfiguredPenaltyValue(),
                entity.getCalculatedPenaltyAmount(), entity.getCreatedAt());
    }
}
