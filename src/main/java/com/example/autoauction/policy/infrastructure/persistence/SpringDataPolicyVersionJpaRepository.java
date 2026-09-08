package com.example.autoauction.policy.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataPolicyVersionJpaRepository extends JpaRepository<JpaPolicyVersionEntity, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE JpaPolicyVersionEntity p SET p.published = true WHERE p.id = :id AND p.published = false")
    int claimPublish(@Param("id") Long id);
}
