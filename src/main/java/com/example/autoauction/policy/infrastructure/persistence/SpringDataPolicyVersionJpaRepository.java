package com.example.autoauction.policy.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SpringDataPolicyVersionJpaRepository extends JpaRepository<JpaPolicyVersionEntity, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE JpaPolicyVersionEntity p SET p.published = true WHERE p.id = :id AND p.published = false")
    int claimPublish(@Param("id") Long id);

    /**
     * Slice 1b-2: deterministic "currently effective" lookup - the published row with the
     * highest versionNumber, never relying on id/insertion-order coincidence.
     */
    java.util.Optional<JpaPolicyVersionEntity> findFirstByPublishedTrueOrderByVersionNumberDesc();

    /** Slice 1: full history (drafts + published), newest first. */
    List<JpaPolicyVersionEntity> findAllByOrderByVersionNumberDesc();

    /** Slice 1: history filtered by publication state, newest first. */
    List<JpaPolicyVersionEntity> findByPublishedOrderByVersionNumberDesc(boolean published);
}
