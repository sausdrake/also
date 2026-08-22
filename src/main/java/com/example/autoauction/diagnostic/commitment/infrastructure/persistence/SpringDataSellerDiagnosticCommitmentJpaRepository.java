// diagnostic/commitment/infrastructure/persistence/SpringDataSellerDiagnosticCommitmentJpaRepository.java
package com.example.autoauction.diagnostic.commitment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public interface SpringDataSellerDiagnosticCommitmentJpaRepository
        extends JpaRepository<JpaSellerDiagnosticCommitmentEntity, Long> {

    Optional<JpaSellerDiagnosticCommitmentEntity> findByDiagnosticRequestId(Long diagnosticRequestId);

    boolean existsByDiagnosticRequestId(Long diagnosticRequestId);

    @Modifying
    @Query("DELETE FROM JpaSellerDiagnosticCommitmentEntity c WHERE c.diagnosticRequestId = :diagnosticRequestId")
    void deleteByDiagnosticRequestId(@Param("diagnosticRequestId") Long diagnosticRequestId);

    // PHASE 2: atomic FROZEN -> AWAITING_FINALIZATION transition. WHERE status='FROZEN' is
    // the sole concurrency guard - matches the atomic UPDATE...WHERE convention already
    // established throughout this codebase (e.g. DiagnosticRequestRepository.bindTemplate).
    // flushAutomatically=true (unlike those siblings) is required here specifically: both
    // callers (DiagnosticRequestService.approveReport/rejectReport) do a plain
    // DiagnosticRequest save() on a DIFFERENT entity/table in the SAME transaction
    // immediately before this call. Hibernate's auto-flush-before-query only flushes
    // entities whose table overlaps the query's own space (seller_diagnostic_commitment),
    // so without an explicit flush here, clearAutomatically=true would silently discard
    // that still-unflushed DiagnosticRequest status change when it clears the persistence
    // context - a real, observed data-loss bug during PHASE 2 test verification.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE JpaSellerDiagnosticCommitmentEntity c SET c.status = 'AWAITING_FINALIZATION', c.updatedAt = :now " +
            "WHERE c.diagnosticRequestId = :diagnosticRequestId AND c.status = 'FROZEN'")
    int markAwaitingFinalization(@Param("diagnosticRequestId") Long diagnosticRequestId, @Param("now") OffsetDateTime now);
}
