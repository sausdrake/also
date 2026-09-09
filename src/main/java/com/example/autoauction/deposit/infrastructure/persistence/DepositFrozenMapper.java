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

        FrozenDeposit domain = new FrozenDeposit(
                entity.getAuctionId(),
                entity.getAmount(),
                entity.getFreezeReason()  // FreezeReason
        );
        // X3-A: the single-arg FrozenDeposit constructor always starts winner=false - the
        // persisted is_winner flag (set via markFrozenAsWinner()) was previously never
        // threaded through on reconstruction, so every caller reading a winner-flagged row
        // back from the database (e.g. releaseWinnerDeposit, and now
        // unblockForSaleFailure/confiscateToPlatform) would silently see isWinner()==false
        // regardless of the real persisted state.
        if (entity.isWinner()) {
            domain.markAsWinner();
        }
        return domain;
    }
}