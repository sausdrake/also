package com.example.autoauction.payment.infrastructure.persistence;

import com.example.autoauction.payment.domain.Payment;
import org.springframework.stereotype.Component;

@Component
public class PaymentMapper {

    public JpaPaymentEntity toEntity(Payment domain) {
        if (domain == null) return null;
        return new JpaPaymentEntity(
                domain.getId(),
                domain.getUserId(),
                domain.getMerchantReference(),
                domain.getAmountRequested(),
                domain.getAmountConfirmed(),
                domain.getCurrency(),
                domain.getStatus(),
                domain.getProviderTransactionId(),
                domain.getProviderReference(),
                domain.getCreationIdempotencyKey(),
                domain.isLateSuccessAfterExpiry(),
                domain.getCreatedAt(),
                domain.getExpiresAt(),
                domain.getUpdatedAt());
    }

    public Payment toDomain(JpaPaymentEntity entity) {
        if (entity == null) return null;
        return new Payment(
                entity.getId(),
                entity.getUserId(),
                entity.getMerchantReference(),
                entity.getAmountRequested(),
                entity.getAmountConfirmed(),
                entity.getCurrency(),
                entity.getStatus(),
                entity.getProviderTransactionId(),
                entity.getProviderReference(),
                entity.getCreationIdempotencyKey(),
                entity.isLateSuccessAfterExpiry(),
                entity.getCreatedAt(),
                entity.getExpiresAt(),
                entity.getUpdatedAt());
    }
}
