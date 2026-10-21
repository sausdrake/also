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
}
