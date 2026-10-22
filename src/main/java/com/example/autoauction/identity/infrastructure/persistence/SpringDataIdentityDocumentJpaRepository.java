package com.example.autoauction.identity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public interface SpringDataIdentityDocumentJpaRepository extends JpaRepository<JpaIdentityDocumentEntity, Long> {

    Optional<JpaIdentityDocumentEntity> findByStorageKey(String storageKey);

    /** KYC-CAPTURE-01: single use - only the first submission of a capture flips consumed_at. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE JpaIdentityDocumentEntity d SET d.consumedAt = :at WHERE d.id = :id AND d.consumedAt IS NULL")
    int markConsumed(@Param("id") Long id, @Param("at") OffsetDateTime at);

    /** KYC-SLOT-01: also never a row any verification or history entry points at (defence in depth). */
    String ABANDONED_CAPTURE = "d.capture_side IS NOT NULL AND d.consumed_at IS NULL AND d.created_at < :before "
            + "AND NOT EXISTS (SELECT 1 FROM identity_verification v WHERE d.id IN "
            + "(v.current_document_id, v.current_document_back_id, v.current_selfie_document_id)) "
            + "AND NOT EXISTS (SELECT 1 FROM identity_verification_history h WHERE d.id IN "
            + "(h.document_reference_id, h.document_back_reference_id))";

    @Query(value = "SELECT d.* FROM identity_document d WHERE " + ABANDONED_CAPTURE + " ORDER BY d.id LIMIT :limit",
            nativeQuery = true)
    java.util.List<JpaIdentityDocumentEntity> findAbandonedCaptures(@Param("before") OffsetDateTime before,
                                                                   @Param("limit") int limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM identity_document d WHERE d.id = :id AND " + ABANDONED_CAPTURE, nativeQuery = true)
    int deleteAbandonedCapture(@Param("id") Long id, @Param("before") OffsetDateTime before);
}
