package com.example.autoauction.auction.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataAuctionParticipantJpaRepository extends JpaRepository<JpaAuctionParticipantEntity, Long> {

    List<JpaAuctionParticipantEntity> findByAuctionId(Long auctionId);

    Optional<JpaAuctionParticipantEntity> findByAuctionIdAndUserId(Long auctionId, Long userId);

    boolean existsByAuctionIdAndUserId(Long auctionId, Long userId);

    @Modifying
    @Query("DELETE FROM JpaAuctionParticipantEntity p WHERE p.auctionId = :auctionId")
    void deleteByAuctionId(@Param("auctionId") Long auctionId);

    @Modifying
    @Query("UPDATE JpaAuctionParticipantEntity p SET p.isWinner = true WHERE p.auctionId = :auctionId AND p.userId = :userId")
    void markAsWinner(@Param("auctionId") Long auctionId, @Param("userId") Long userId);
}