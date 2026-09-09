package com.example.autoauction.diagnostic.commitment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataSellerViolationResolutionJpaRepository extends JpaRepository<JpaSellerViolationResolutionEntity, Long> {

    Optional<JpaSellerViolationResolutionEntity> findBySellerDiagnosticCommitmentId(Long sellerDiagnosticCommitmentId);
}
