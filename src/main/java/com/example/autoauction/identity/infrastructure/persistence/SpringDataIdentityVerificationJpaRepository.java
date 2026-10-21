package com.example.autoauction.identity.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataIdentityVerificationJpaRepository extends JpaRepository<JpaIdentityVerificationEntity, Long> {

    Optional<JpaIdentityVerificationEntity> findByUserId(Long userId);

    List<JpaIdentityVerificationEntity> findByUserIdIn(Collection<Long> userIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT iv FROM JpaIdentityVerificationEntity iv WHERE iv.id = :id")
    Optional<JpaIdentityVerificationEntity> findByIdWithLock(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT iv FROM JpaIdentityVerificationEntity iv WHERE iv.userId = :userId")
    Optional<JpaIdentityVerificationEntity> findByUserIdWithLock(@Param("userId") Long userId);

    /** Admin pending queue - backed by the partial index on status='PENDING_REVIEW' (V47). */
    List<JpaIdentityVerificationEntity> findByStatusOrderByCreatedAtAsc(
            com.example.autoauction.identity.domain.IdentityVerificationStatus status);
}
