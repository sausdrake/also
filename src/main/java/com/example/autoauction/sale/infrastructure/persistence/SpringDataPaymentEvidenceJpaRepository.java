package com.example.autoauction.sale.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataPaymentEvidenceJpaRepository extends JpaRepository<JpaPaymentEvidenceEntity, Long> {

    Optional<JpaPaymentEvidenceEntity> findByAuctionId(Long auctionId);

    boolean existsByAuctionId(Long auctionId);
}
