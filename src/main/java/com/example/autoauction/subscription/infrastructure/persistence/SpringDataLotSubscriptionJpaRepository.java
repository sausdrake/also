package com.example.autoauction.subscription.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataLotSubscriptionJpaRepository
        extends JpaRepository<JpaLotSubscriptionEntity, Long> {

    boolean existsByUserIdAndAuctionId(Long userId, Long auctionId);
    void deleteByUserIdAndAuctionId(Long userId, Long auctionId);
    List<JpaLotSubscriptionEntity> findByUserId(Long userId);
    List<JpaLotSubscriptionEntity> findByAuctionId(Long auctionId);
}
