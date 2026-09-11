package com.example.autoauction.deposit.infrastructure.persistence;

import com.example.autoauction.deposit.domain.Deposit;
import com.example.autoauction.deposit.domain.DepositTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DepositMapper {

    public JpaDepositEntity toEntity(Deposit domain) {
        if (domain == null) return null;

        JpaDepositEntity entity = new JpaDepositEntity(
                domain.getUserId(),
                domain.getBalance(),
                domain.getBlockedAmount(),
                domain.getStatus()
        );
        entity.setId(domain.getId());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        entity.setVersion(domain.getVersion());

        return entity;
    }

    public Deposit toDomain(JpaDepositEntity entity) {
        if (entity == null) return null;

        Deposit deposit = new Deposit(
                entity.getUserId(),
                entity.getBalance()
        );
        deposit.setId(entity.getId());
        deposit.setBlockedAmount(entity.getBlockedAmount());
        deposit.setStatus(entity.getStatus());
        deposit.setCreatedAt(entity.getCreatedAt());
        deposit.setUpdatedAt(entity.getUpdatedAt());
        deposit.setVersion(entity.getVersion());

        return deposit;
    }

    public JpaDepositTransactionEntity toEntity(DepositTransaction domain) {
        if (domain == null) return null;

        JpaDepositTransactionEntity entity = new JpaDepositTransactionEntity(
                domain.getDepositId(),
                domain.getUserId(),
                domain.getAuctionId(),
                domain.getType(),
                domain.getAmount(),
                domain.getBalanceBefore(),
                domain.getBalanceAfter(),
                domain.getReference(),
                domain.getDescription()
        );
        entity.setIdempotencyKey(domain.getIdempotencyKey());
        entity.setPerformedByAdminId(domain.getPerformedByAdminId());
        return entity;
    }

    public DepositTransaction toDomain(JpaDepositTransactionEntity entity) {
        if (entity == null) return null;

        DepositTransaction transaction = new DepositTransaction(
                entity.getDepositId(),
                entity.getUserId(),
                entity.getType(),
                entity.getAmount(),
                entity.getBalanceBefore(),
                entity.getBalanceAfter(),
                entity.getReference(),
                entity.getDescription()
        );
        transaction.setId(entity.getId());
        transaction.setAuctionId(entity.getAuctionId());
        transaction.setIdempotencyKey(entity.getIdempotencyKey());
        transaction.setCreatedAt(entity.getCreatedAt());
        transaction.setPerformedByAdminId(entity.getPerformedByAdminId());

        return transaction;
    }
}