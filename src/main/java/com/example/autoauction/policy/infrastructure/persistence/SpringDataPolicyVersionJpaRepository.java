package com.example.autoauction.policy.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface SpringDataPolicyVersionJpaRepository extends JpaRepository<JpaPolicyVersionEntity, Long> {

    /**
     * Locked concurrency design, section B/D: the one atomic claim statement - publication and
     * the review-to-publish freshness check happen in the SAME WHERE clause, so a concurrent
     * edit committing between the caller's pre-check and this statement cannot slip through.
     * Lineage is deliberately not part of this WHERE clause - see
     * {@link PolicyVersionRepositoryAdapter#claimPublish} and the port interface's Javadoc.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE JpaPolicyVersionEntity p SET p.published = true, p.publishedBy = :publishedBy, " +
            "p.publishedAt = :publishedAt " +
            "WHERE p.id = :id AND p.published = false AND p.configurationRevision = :observedConfigurationRevision")
    int claimPublish(@Param("id") Long id, @Param("publishedBy") Long publishedBy,
                      @Param("publishedAt") OffsetDateTime publishedAt,
                      @Param("observedConfigurationRevision") Long observedConfigurationRevision);

    /**
     * Locked concurrency design, section B: the only legitimate way configurationRevision ever
     * changes - a genuine atomic in-database increment, never a read-modify-write.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE JpaPolicyVersionEntity p SET p.configurationRevision = p.configurationRevision + 1 WHERE p.id = :id")
    void incrementConfigurationRevision(@Param("id") Long id);

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
