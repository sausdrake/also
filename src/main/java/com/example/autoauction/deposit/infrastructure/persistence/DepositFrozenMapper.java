// deposit/infrastructure/persistence/DepositFrozenMapper.java
package com.example.autoauction.deposit.infrastructure.persistence;

import com.example.autoauction.deposit.domain.FreezeReason;
import com.example.autoauction.deposit.domain.FrozenDeposit;
import org.springframework.stereotype.Component;

@Component
public class DepositFrozenMapper {

    public JpaDepositFrozenEntity toEntity(FrozenDeposit domain, Long depositId) {
        if (domain == null) return null;

        JpaDepositFrozenEntity entity = new JpaDepositFrozenEntity(
                depositId,
                domain.getAuctionId(),
                domain.getAmount(),
                domain.getReason()  // FreezeReason
        );
        entity.setWinner(domain.isWinner());
        entity.setFrozenAt(domain.getFrozenAt());
        return entity;
    }

    public FrozenDeposit toDomain(JpaDepositFrozenEntity entity) {
        if (entity == null) return null;

        return new FrozenDeposit(
                entity.getAuctionId(),
                entity.getAmount(),
                entity.getFreezeReason()  // FreezeReason
        );
    }
}