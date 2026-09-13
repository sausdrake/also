package com.example.autoauction.payment.infrastructure.persistence;

import com.example.autoauction.payment.domain.PaymentEvent;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventMapper {

    public JpaPaymentEventEntity toEntity(PaymentEvent domain) {
        if (domain == null) return null;
        return new JpaPaymentEventEntity(
                domain.getId(),
                domain.getPaymentId(),
                domain.getType(),
                domain.getSource(),
                domain.getRawPayload(),
                domain.getOccurredAt());
    }

    public PaymentEvent toDomain(JpaPaymentEventEntity entity) {
        if (entity == null) return null;
        return new PaymentEvent(
                entity.getId(),
                entity.getPaymentId(),
                entity.getType(),
                entity.getSource(),
                entity.getRawPayload(),
                entity.getOccurredAt());
    }
}
