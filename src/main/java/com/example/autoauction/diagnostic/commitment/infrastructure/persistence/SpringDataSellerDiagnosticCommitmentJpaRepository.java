// diagnostic/commitment/infrastructure/persistence/SpringDataSellerDiagnosticCommitmentJpaRepository.java
package com.example.autoauction.diagnostic.commitment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SpringDataSellerDiagnosticCommitmentJpaRepository
        extends JpaRepository<JpaSellerDiagnosticCommitmentEntity, Long> {

    Optional<JpaSellerDiagnosticCommitmentEntity> findByDiagnosticRequestId(Long diagnosticRequestId);

    boolean existsByDiagnosticRequestId(Long diagnosticRequestId);

    @Modifying
    @Query("DELETE FROM JpaSellerDiagnosticCommitmentEntity c WHERE c.diagnosticRequestId = :diagnosticRequestId")
    void deleteByDiagnosticRequestId(@Param("diagnosticRequestId") Long diagnosticRequestId);
}
