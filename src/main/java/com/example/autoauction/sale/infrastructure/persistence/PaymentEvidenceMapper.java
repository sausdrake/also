package com.example.autoauction.sale.infrastructure.persistence;

import com.example.autoauction.sale.domain.PaymentEvidence;
import org.springframework.stereotype.Component;

@Component
public class PaymentEvidenceMapper {

    public JpaPaymentEvidenceEntity toEntity(PaymentEvidence domain) {
        if (domain == null) return null;
        JpaPaymentEvidenceEntity entity = new JpaPaymentEvidenceEntity();
        entity.setId(domain.getId());
        entity.setAuctionId(domain.getAuctionId());
        entity.setBuyerId(domain.getBuyerId());
        entity.setAmount(domain.getAmount());
        entity.setReceivedByAdminId(domain.getReceivedByAdminId());
        entity.setReceivedAt(domain.getReceivedAt());
        entity.setCreatedAt(domain.getCreatedAt());
        return entity;
    }

    public PaymentEvidence toDomain(JpaPaymentEvidenceEntity entity) {
        if (entity == null) return null;
        return new PaymentEvidence(entity.getId(), entity.getAuctionId(), entity.getBuyerId(),
                entity.getAmount(), entity.getReceivedByAdminId(), entity.getReceivedAt(), entity.getCreatedAt());
    }
}
