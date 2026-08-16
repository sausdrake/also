// common/infrastructure/persistence/SpringDataFailedOperationJpaRepository.java
package com.example.autoauction.common.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface SpringDataFailedOperationJpaRepository extends JpaRepository<JpaFailedOperationEntity, Long> {

    List<JpaFailedOperationEntity> findByStatus(String status);

    @Query("SELECT f FROM JpaFailedOperationEntity f WHERE f.status = :pendingStatus " +
           "AND (f.nextRetryAt IS NULL OR f.nextRetryAt <= :now)")
    List<JpaFailedOperationEntity> findRetryablePending(@Param("pendingStatus") String pendingStatus,
                                                        @Param("now") OffsetDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE JpaFailedOperationEntity f SET f.status = :processingStatus, f.updatedAt = :now " +
           "WHERE f.id = :id AND f.status = :pendingStatus")
    int claim(@Param("id") Long id, @Param("pendingStatus") String pendingStatus,
             @Param("processingStatus") String processingStatus, @Param("now") OffsetDateTime now);
}