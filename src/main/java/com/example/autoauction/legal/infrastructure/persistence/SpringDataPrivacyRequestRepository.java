package com.example.autoauction.legal.infrastructure.persistence;

import com.example.autoauction.legal.domain.PrivacyRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SpringDataPrivacyRequestRepository extends JpaRepository<JpaPrivacyRequestEntity, Long> {

    List<JpaPrivacyRequestEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<JpaPrivacyRequestEntity> findAllByOrderByCreatedAtDesc();

    List<JpaPrivacyRequestEntity> findByStatusOrderByCreatedAtDesc(PrivacyRequestStatus status);

    long countByUserIdAndStatusIn(Long userId, Collection<PrivacyRequestStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM JpaPrivacyRequestEntity r WHERE r.id = :id")
    Optional<JpaPrivacyRequestEntity> findByIdForUpdate(@Param("id") Long id);
}
