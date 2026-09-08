package com.example.autoauction.sale.infrastructure.persistence;

import com.example.autoauction.sale.domain.FinancialResolutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SpringDataSaleResolutionJpaRepository extends JpaRepository<JpaSaleResolutionEntity, Long> {

    Optional<JpaSaleResolutionEntity> findByAuctionId(Long auctionId);

    boolean existsByAuctionId(Long auctionId);

    /**
     * Single internal claim-first UPDATE, parameterized by from/to - kept private to this
     * infrastructure interface. SaleResolutionRepositoryAdapter exposes three specifically-
     * named, hardcoded-argument public methods on top of this (claimFinancialResolutionDone,
     * claimFinancialResolutionFailed, resetFinancialResolutionToPending) so the actual PORT
     * the application layer sees never offers a generic "transition to any status" capability -
     * this method existing at the SpringData layer is pure implementation-detail deduplication,
     * not an exposed generic update API.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE JpaSaleResolutionEntity s SET s.financialResolutionStatus = :to " +
            "WHERE s.id = :id AND s.financialResolutionStatus = :from")
    int claimFinancialResolutionTransition(@Param("id") Long id,
                                            @Param("from") FinancialResolutionStatus from,
                                            @Param("to") FinancialResolutionStatus to);
}
