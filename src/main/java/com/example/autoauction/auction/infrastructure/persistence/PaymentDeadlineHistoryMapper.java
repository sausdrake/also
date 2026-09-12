package com.example.autoauction.auction.infrastructure.persistence;

import com.example.autoauction.auction.domain.PaymentDeadlineHistory;
import org.springframework.stereotype.Component;

@Component
public class PaymentDeadlineHistoryMapper {

    public JpaPaymentDeadlineHistoryEntity toEntity(PaymentDeadlineHistory domain) {
        if (domain == null) return null;
        return new JpaPaymentDeadlineHistoryEntity(
                domain.getId(),
                domain.getAuctionId(),
                domain.getChangeType(),
                domain.getPreviousDeadline(),
                domain.getNewDeadline(),
                domain.getReason(),
                domain.getChangedByAdminId(),
                domain.getChangedAt());
    }

    public PaymentDeadlineHistory toDomain(JpaPaymentDeadlineHistoryEntity entity) {
        if (entity == null) return null;
        return new PaymentDeadlineHistory(
                entity.getId(),
                entity.getAuctionId(),
                entity.getChangeType(),
                entity.getPreviousDeadline(),
                entity.getNewDeadline(),
                entity.getReason(),
                entity.getChangedByAdminId(),
                entity.getChangedAt());
    }
}
