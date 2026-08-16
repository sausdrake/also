package com.example.autoauction.deposit.infrastructure.persistence;

import com.example.autoauction.deposit.domain.DepositTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface SpringDataDepositTransactionJpaRepository extends JpaRepository<JpaDepositTransactionEntity, Long> {
    List<JpaDepositTransactionEntity> findByUserId(Long userId);
    List<JpaDepositTransactionEntity> findByDepositId(Long depositId);
    List<JpaDepositTransactionEntity> findByAuctionId(Long auctionId);
    java.util.Optional<JpaDepositTransactionEntity> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT t FROM JpaDepositTransactionEntity t WHERE t.userId = :userId " +
           "AND (:type IS NULL OR t.type = :type) " +
           "AND (:from IS NULL OR t.createdAt >= :from) " +
           "AND (:to IS NULL OR t.createdAt <= :to) " +
           "ORDER BY t.createdAt DESC")
    List<JpaDepositTransactionEntity> findByUserIdFiltered(
            @Param("userId") Long userId,
            @Param("type") DepositTransaction.TransactionType type,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);
}