package com.example.autoauction.legal.infrastructure.persistence;

import com.example.autoauction.legal.domain.AcceptanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringDataLegalAcceptanceRepository extends JpaRepository<JpaLegalAcceptanceEntity, Long> {

    Optional<JpaLegalAcceptanceEntity> findByUserIdAndDocumentVersionId(Long userId, Long documentVersionId);

    boolean existsByUserIdAndDocumentVersionIdAndStatus(Long userId, Long documentVersionId, AcceptanceStatus status);

    List<JpaLegalAcceptanceEntity> findByUserIdOrderByAcceptedAtDesc(Long userId);

    /** Serialises the acceptances of one user (row lock on the user), so a double submit records once. */
    @Query(value = "SELECT id FROM users WHERE id = :userId FOR UPDATE", nativeQuery = true)
    Long lockUser(@Param("userId") Long userId);
}
