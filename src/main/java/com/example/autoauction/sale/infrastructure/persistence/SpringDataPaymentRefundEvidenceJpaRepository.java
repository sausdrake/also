package com.example.autoauction.sale.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataPaymentRefundEvidenceJpaRepository
        extends JpaRepository<JpaPaymentRefundEvidenceEntity, Long> {

    Optional<JpaPaymentRefundEvidenceEntity> findByAuctionId(Long auctionId);

    Optional<JpaPaymentRefundEvidenceEntity> findByPaymentEvidenceId(Long paymentEvidenceId);

    boolean existsByAuctionId(Long auctionId);

    boolean existsByPaymentEvidenceId(Long paymentEvidenceId);
}
