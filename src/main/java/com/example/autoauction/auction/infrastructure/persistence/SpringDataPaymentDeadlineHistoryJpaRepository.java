package com.example.autoauction.auction.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataPaymentDeadlineHistoryJpaRepository extends JpaRepository<JpaPaymentDeadlineHistoryEntity, Long> {

    List<JpaPaymentDeadlineHistoryEntity> findByAuctionIdOrderByChangedAtAscIdAsc(Long auctionId);
}
