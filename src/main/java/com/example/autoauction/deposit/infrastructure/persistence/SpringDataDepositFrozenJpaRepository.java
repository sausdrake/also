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

    // Boolean-returning (no entity hydration) - deliberately used for R-2's post-retry
    // state verification instead of findByDepositId/findByDepositIdAndAuctionId, which
    // return managed JpaDepositFrozenEntity instances and are therefore subject to
    // Hibernate's first-level identity-map returning an already-loaded (stale) instance
    // rather than re-reading fresh column values - confirmed by direct reproduction: a
    // bulk UPDATE's effect was visible via a native SQL query but NOT via
    // findByDepositId(...).stream()...isWinner() in the same test. exists/count-style
    // derived queries return a primitive directly from the SQL result, never touching the
    // identity map, and were confirmed unaffected.
    boolean existsByDepositIdAndAuctionIdAndIsWinnerFalse(Long depositId, Long auctionId);

    @Modifying
    @Query("DELETE FROM JpaDepositFrozenEntity f WHERE f.auctionId = :auctionId")
    void deleteByAuctionId(@Param("auctionId") Long auctionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE JpaDepositFrozenEntity f SET f.isWinner = true WHERE f.depositId = :depositId AND f.auctionId = :auctionId")
    int markAsWinner(@Param("depositId") Long depositId, @Param("auctionId") Long auctionId);
    boolean existsByDepositIdAndAuctionId(Long depositId, Long auctionId);
}