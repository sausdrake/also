package com.example.autoauction.diagnostic.commitment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataSellerViolationResolutionItemJpaRepository extends JpaRepository<JpaSellerViolationResolutionItemEntity, Long> {

    List<JpaSellerViolationResolutionItemEntity> findBySellerViolationResolutionId(Long sellerViolationResolutionId);
}
