package com.example.autoauction.diagnostic.media.infrastructure.persistence;

import com.example.autoauction.diagnostic.media.domain.UploadStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface SpringDataDiagnosticMediaJpaRepository extends JpaRepository<JpaDiagnosticMediaEntity, Long> {

    List<JpaDiagnosticMediaEntity> findByDiagnosticRequestIdAndInspectionItemIdOrderBySortOrderAsc(
            Long diagnosticRequestId, Long inspectionItemId);

    List<JpaDiagnosticMediaEntity> findByDiagnosticRequestId(Long diagnosticRequestId);

    List<JpaDiagnosticMediaEntity> findByUploadStatusAndCreatedAtBefore(UploadStatus uploadStatus, OffsetDateTime cutoff);

    List<JpaDiagnosticMediaEntity> findByUploadStatus(UploadStatus uploadStatus);

    // MEDIA-3.2: same atomic-conditional-transition discipline as
    // DiagnosticRequest.bindTemplate (MEDIA-3.1A) - the WHERE clause is the
    // sole concurrency/idempotency guard for finalize/expire/promote races.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE JpaDiagnosticMediaEntity m SET m.uploadStatus = :toStatus, m.updatedAt = :now " +
            "WHERE m.id = :id AND m.uploadStatus = :fromStatus")
    int updateUploadStatusIfCurrent(@Param("id") Long id, @Param("fromStatus") UploadStatus fromStatus,
                                    @Param("toStatus") UploadStatus toStatus, @Param("now") OffsetDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE JpaDiagnosticMediaEntity m SET m.sortOrder = :sortOrder, m.updatedAt = :now WHERE m.id = :id")
    void updateSortOrder(@Param("id") Long id, @Param("sortOrder") Integer sortOrder, @Param("now") OffsetDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE JpaDiagnosticMediaEntity m SET m.uploadStatus = 'READY', m.mimeType = :mimeType, " +
            "m.sizeBytes = :sizeBytes, m.updatedAt = :now WHERE m.id = :id AND m.uploadStatus = 'PENDING_UPLOAD'")
    int finalizeReady(@Param("id") Long id, @Param("mimeType") String mimeType,
                      @Param("sizeBytes") long sizeBytes, @Param("now") OffsetDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE JpaDiagnosticMediaEntity m SET m.uploadStatus = 'FAILED', m.updatedAt = :now " +
            "WHERE m.id = :id AND m.uploadStatus = 'PENDING_UPLOAD'")
    int markFailed(@Param("id") Long id, @Param("now") OffsetDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE JpaDiagnosticMediaEntity m SET m.uploadStatus = 'DELETED', m.updatedAt = :now " +
            "WHERE m.id = :id AND m.uploadStatus <> 'DELETED'")
    int softDelete(@Param("id") Long id, @Param("now") OffsetDateTime now);
}
