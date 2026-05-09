// common/infrastructure/persistence/SpringDataFailedOperationJpaRepository.java
package com.example.autoauction.common.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SpringDataFailedOperationJpaRepository extends JpaRepository<JpaFailedOperationEntity, Long> {

    List<JpaFailedOperationEntity> findByStatus(String status);

    @Modifying
    @Query("UPDATE JpaFailedOperationEntity f SET f.status = 'PROCESSED', f.updatedAt = :now WHERE f.id = :id")
    void markAsProcessed(@Param("id") Long id, @Param("now") java.time.OffsetDateTime now);
}