package com.example.autoauction.legal.infrastructure.persistence;

import com.example.autoauction.legal.domain.LegalDocumentStatus;
import com.example.autoauction.legal.domain.LegalDocumentType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringDataLegalDocumentVersionRepository extends JpaRepository<JpaLegalDocumentVersionEntity, Long> {

    List<JpaLegalDocumentVersionEntity> findByStatusOrderByDocumentTypeAsc(LegalDocumentStatus status);

    Optional<JpaLegalDocumentVersionEntity> findByDocumentTypeAndStatus(LegalDocumentType type, LegalDocumentStatus status);

    boolean existsByDocumentTypeAndVersion(LegalDocumentType type, String version);

    List<JpaLegalDocumentVersionEntity> findAllByOrderByDocumentTypeAscCreatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM JpaLegalDocumentVersionEntity d WHERE d.id = :id")
    Optional<JpaLegalDocumentVersionEntity> findByIdForUpdate(@Param("id") Long id);

    /** Serialises activations of one document type (the previous ACTIVE is retired in the same transaction). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM JpaLegalDocumentVersionEntity d WHERE d.documentType = :type AND d.status = :status")
    Optional<JpaLegalDocumentVersionEntity> findByDocumentTypeAndStatusForUpdate(@Param("type") LegalDocumentType type,
                                                                                @Param("status") LegalDocumentStatus status);
}
