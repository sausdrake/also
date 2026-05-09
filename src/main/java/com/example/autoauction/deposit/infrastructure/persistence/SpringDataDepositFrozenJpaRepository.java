// deposit/infrastructure/persistence/SpringDataDepositFrozenJpaRepository.java
package com.example.autoauction.deposit.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataDepositFrozenJpaRepository extends JpaRepository<JpaDepositFrozenEntity, Long> {

    List<JpaDepositFrozenEntity> findByDepositId(Long depositId);

    List<JpaDepositFrozenEntity> findByAuctionId(Long auctionId);

    Optional<JpaDepositFrozenEntity> findByDepositIdAndAuctionId(Long depositId, Long auctionId);

    @Modifying
    @Query("DELETE FROM JpaDepositFrozenEntity f WHERE f.auctionId = :auctionId")
    void deleteByAuctionId(@Param("auctionId") Long auctionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE JpaDepositFrozenEntity f SET f.isWinner = true WHERE f.depositId = :depositId AND f.auctionId = :auctionId")
    void markAsWinner(@Param("depositId") Long depositId, @Param("auctionId") Long auctionId);
    boolean existsByDepositIdAndAuctionId(Long depositId, Long auctionId);
}