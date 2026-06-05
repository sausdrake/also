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
import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataDiagnosticRequestJpaRepository extends JpaRepository<JpaDiagnosticRequestEntity, Long> {

    Page<JpaDiagnosticRequestEntity> findByUserId(Long userId, Pageable pageable);

    Page<JpaDiagnosticRequestEntity> findByStatus(DiagnosticRequest.DiagnosticRequestStatus status, Pageable pageable);

    Page<JpaDiagnosticRequestEntity> findByAssignedTo(Long assignedTo, Pageable pageable);

    List<JpaDiagnosticRequestEntity> findByStatusAndAssignedToIsNull(DiagnosticRequest.DiagnosticRequestStatus status);

    Optional<JpaDiagnosticRequestEntity> findByIdAndUserId(Long id, Long userId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE JpaDiagnosticRequestEntity d SET d.assignedTo = :diagnosticId, d.status = 'IN_PROGRESS' WHERE d.id = :requestId AND d.status = 'PENDING'")
    int assignToDiagnostic(@Param("requestId") Long requestId, @Param("diagnosticId") Long diagnosticId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE JpaDiagnosticRequestEntity d SET d.status = 'COMPLETED', d.updatedAt = :now WHERE d.id = :requestId AND d.assignedTo = :diagnosticId")
    int markAsCompleted(@Param("requestId") Long requestId, @Param("diagnosticId") Long diagnosticId, @Param("now") OffsetDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE JpaDiagnosticRequestEntity d SET d.status = 'IN_PROGRESS', d.rejectedReason = NULL, d.updatedAt = :now WHERE d.id = :requestId AND d.assignedTo = :diagnosticId AND d.status = 'REJECTED'")
    int resetForRework(@Param("requestId") Long requestId, @Param("diagnosticId") Long diagnosticId, @Param("now") OffsetDateTime now);
}