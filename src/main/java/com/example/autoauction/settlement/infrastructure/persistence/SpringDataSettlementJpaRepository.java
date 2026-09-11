package com.example.autoauction.settlement.infrastructure.persistence;

import com.example.autoauction.settlement.domain.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

public interface SpringDataSettlementJpaRepository extends JpaRepository<JpaSettlementEntity, Long> {

    Optional<JpaSettlementEntity> findByAuctionId(Long auctionId);

    boolean existsByAuctionId(Long auctionId);

    /**
     * Single claim-first UPDATE, mirroring
     * {@code SpringDataSaleResolutionJpaRepository.claimFinancialResolutionTransition}'s own
     * pattern exactly - the sole concurrency guard for the PENDING -&gt; PAYOUT_RECORDED
     * transition. Returns the number of rows matched (0 or 1).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE JpaSettlementEntity s SET s.status = :toStatus, s.attestedPayoutAmount = :amount, "
            + "s.recordedByAdminId = :adminId, s.recordedAt = :recordedAt "
            + "WHERE s.id = :id AND s.status = :fromStatus")
    int claimPayoutRecordedTransition(@Param("id") Long id,
                                       @Param("fromStatus") SettlementStatus fromStatus,
                                       @Param("toStatus") SettlementStatus toStatus,
                                       @Param("amount") BigDecimal amount,
                                       @Param("adminId") Long adminId,
                                       @Param("recordedAt") OffsetDateTime recordedAt);
}
