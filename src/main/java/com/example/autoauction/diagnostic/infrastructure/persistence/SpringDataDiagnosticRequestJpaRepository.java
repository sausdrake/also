package com.example.autoauction.diagnostic.infrastructure.persistence;

import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataDiagnosticRequestJpaRepository extends JpaRepository<JpaDiagnosticRequestEntity, Long> {

    Page<JpaDiagnosticRequestEntity> findByUserId(Long userId, Pageable pageable);

    boolean existsByUserIdAndStatusIn(Long userId, Collection<DiagnosticRequest.DiagnosticRequestStatus> statuses);

    boolean existsByVinAndStatusIn(String vin, Collection<DiagnosticRequest.DiagnosticRequestStatus> statuses);

    Page<JpaDiagnosticRequestEntity> findByStatus(DiagnosticRequest.DiagnosticRequestStatus status, Pageable pageable);

    Page<JpaDiagnosticRequestEntity> findByAssignedTo(Long assignedTo, Pageable pageable);

    List<JpaDiagnosticRequestEntity> findByStatusAndAssignedToIsNull(DiagnosticRequest.DiagnosticRequestStatus status);

    Optional<JpaDiagnosticRequestEntity> findByIdAndUserId(Long id, Long userId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE JpaDiagnosticRequestEntity d SET d.assignedTo = :diagnosticId, d.status = 'IN_PROGRESS' WHERE d.id = :requestId AND d.status = 'PENDING'")
    int assignToDiagnostic(@Param("requestId") Long requestId, @Param("diagnosticId") Long diagnosticId);

    // MEDIA-3.1A: atomic conditional bind - template_id IS NULL is both the
    // immutability guard (a second bind attempt can never match) and the
    // concurrency guard (of two racing binds, only one UPDATE can match this
    // WHERE clause; see DiagnosticRequestService.bindTemplate for how the
    // loser interprets an affected-row count of 0).
    @Modifying(clearAutomatically = true)
    @Query("UPDATE JpaDiagnosticRequestEntity d SET d.templateId = :templateId, d.updatedAt = :now " +
            "WHERE d.id = :requestId AND d.assignedTo = :diagnosticId AND d.status = 'IN_PROGRESS' AND d.templateId IS NULL")
    int bindTemplate(@Param("requestId") Long requestId, @Param("diagnosticId") Long diagnosticId,
                     @Param("templateId") Long templateId, @Param("now") OffsetDateTime now);

    // MEDIA-3.1B: historical backfill bind - deliberately a SEPARATE guard shape
    // from bindTemplate() above, not a modification of it. The backfill runs as
    // a system/admin operation, not a diagnostician action: it has no
    // diagnosticId to check and applies to requests in ANY already-non-PENDING
    // status (IN_PROGRESS/COMPLETED/APPROVED/REJECTED), not just IN_PROGRESS.
    // template_id IS NULL is still the sole guard, preserving the same
    // never-overwrite-an-existing-binding invariant.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE JpaDiagnosticRequestEntity d SET d.templateId = :templateId, d.updatedAt = :now " +
            "WHERE d.id = :requestId AND d.templateId IS NULL")
    int bindTemplateIfUnset(@Param("requestId") Long requestId, @Param("templateId") Long templateId,
                            @Param("now") OffsetDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE JpaDiagnosticRequestEntity d SET d.status = 'COMPLETED', d.updatedAt = :now WHERE d.id = :requestId AND d.assignedTo = :diagnosticId")
    int markAsCompleted(@Param("requestId") Long requestId, @Param("diagnosticId") Long diagnosticId, @Param("now") OffsetDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE JpaDiagnosticRequestEntity d SET d.status = 'IN_PROGRESS', d.rejectedReason = NULL, d.updatedAt = :now WHERE d.id = :requestId AND d.assignedTo = :diagnosticId AND d.status = 'REJECTED'")
    int resetForRework(@Param("requestId") Long requestId, @Param("diagnosticId") Long diagnosticId, @Param("now") OffsetDateTime now);

    Optional<JpaDiagnosticRequestEntity> findFirstByVinAndStatusOrderByUpdatedAtDesc(
            String vin, DiagnosticRequest.DiagnosticRequestStatus status);
}