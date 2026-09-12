package com.example.autoauction.identity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SpringDataIdentityVerificationHistoryJpaRepository extends JpaRepository<JpaIdentityVerificationHistoryEntity, Long> {

    List<JpaIdentityVerificationHistoryEntity> findByIdentityVerificationIdOrderByOccurredAtAscIdAsc(Long identityVerificationId);
}
