package com.example.autoauction.sale.infrastructure.persistence;

import com.example.autoauction.sale.domain.PaymentRefundEvidence;
import org.springframework.stereotype.Component;

@Component
public class PaymentRefundEvidenceMapper {

    public JpaPaymentRefundEvidenceEntity toEntity(PaymentRefundEvidence domain) {
        if (domain == null) return null;
        JpaPaymentRefundEvidenceEntity entity = new JpaPaymentRefundEvidenceEntity();
        entity.setId(domain.getId());
        entity.setPaymentEvidenceId(domain.getPaymentEvidenceId());
        entity.setAuctionId(domain.getAuctionId());
        entity.setBuyerId(domain.getBuyerId());
        entity.setRefundedAmount(domain.getRefundedAmount());
        entity.setRefundedByAdminId(domain.getRefundedByAdminId());
        entity.setRefundedAt(domain.getRefundedAt());
        entity.setCreatedAt(domain.getCreatedAt());
        return entity;
    }

    public PaymentRefundEvidence toDomain(JpaPaymentRefundEvidenceEntity entity) {
        if (entity == null) return null;
        return new PaymentRefundEvidence(entity.getId(), entity.getPaymentEvidenceId(), entity.getAuctionId(),
                entity.getBuyerId(), entity.getRefundedAmount(), entity.getRefundedByAdminId(),
                entity.getRefundedAt(), entity.getCreatedAt());
    }
}
