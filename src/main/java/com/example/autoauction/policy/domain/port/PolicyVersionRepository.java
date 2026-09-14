package com.example.autoauction.policy.domain.port;

import com.example.autoauction.policy.domain.PolicyVersion;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Slice 1b-1: minimal port surface - create/persist a PolicyVersion, retrieve one, and
 * atomically claim its publication. Deliberately excludes update/delete (PolicyVersion has no
 * legitimate update path beyond publication) and any auction-selection query (that belongs to
 * Slice 1b-2).
 */
public interface PolicyVersionRepository {

    PolicyVersion save(PolicyVersion policyVersion);

    Optional<PolicyVersion> findById(Long id);

    /**
     * Atomically claims the publish transition for the PolicyVersion with the given id AND
     * establishes its publication metadata in the SAME statement - {@code UPDATE ... SET
     * published = true, published_by = :publishedBy, published_at = :publishedAt WHERE id = :id
     * AND published = false AND configuration_revision = :observedConfigurationRevision},
     * checked via rows-affected. The {@code configuration_revision} equality check is embedded
     * directly in this one atomic statement (not a separate SELECT-then-UPDATE) - this is the
     * mechanism that actually closes the "review-to-publish freshness" race (Decision 6): a
     * concurrent edit committing between the caller's own freshness pre-check and this claim
     * would otherwise slip through undetected.
     *
     * <p>Returns {@code true} only if this call won the claim (the row existed, was not already
     * published, AND its configuration_revision still matched); {@code false} otherwise - the
     * caller is responsible for distinguishing "not found"/"already published" from "stale
     * configuration" if it needs to, by a subsequent findById + revision comparison.
     *
     * <p>Lineage (Decision 5) is deliberately NOT part of this statement's WHERE clause - it is
     * protected purely by the mutual exclusion of {@link #lockPublicationSerializationPoint()},
     * which the caller MUST hold for the entire lineage-check-then-claim sequence (see the
     * locked concurrency design, section E).
     */
    boolean claimPublish(Long id, Long publishedBy, OffsetDateTime publishedAt, Long observedConfigurationRevision);

    /**
     * Locked concurrency design, section D: acquires an exclusive lock on the single
     * policy_version_counter row (the SAME row/primitive already used by version-number
     * allocation in {@code save}) for the remainder of the caller's transaction, serializing
     * every publication attempt against every other one. Must be called BEFORE re-reading
     * "current published" and validating lineage - while held, no other publish attempt can
     * commit a change to "current published" out from under the caller. Deliberately reuses
     * this existing primitive rather than introducing a second, separate global lock.
     */
    void lockPublicationSerializationPoint();

    /**
     * Locked concurrency design, section B: atomically increments the aggregate configuration
     * revision counter for one PolicyVersion - {@code UPDATE ... SET configuration_revision =
     * configuration_revision + 1 WHERE id = :policyVersionId} - a genuine in-database increment,
     * never a read-modify-write from application code (which would itself race: two concurrent
     * readers of the same old value would both write old+1, losing one increment). Must be
     * called in the SAME transaction as the configuration mutation it accompanies, so that a
     * rolled-back mutation rolls this back too.
     */
    void incrementConfigurationRevision(Long policyVersionId);

    /**
     * Slice 1b-2: the currently effective PolicyVersion for NEW auction creation - the
     * published row with the highest versionNumber. Deterministic by construction:
     * versionNumber is allocated from the Slice 1b-1 counter table under a pessimistic lock,
     * guaranteeing uniqueness and monotonicity, so ordering by it (never by id or insertion
     * order) is never accidental. Returns empty when no PolicyVersion has ever been published -
     * callers must fail closed in that case, never fall back to a default/live setting.
     */
    Optional<PolicyVersion> findCurrentPublished();

    /**
     * Slice 1 (Policy Administration): all PolicyVersions (drafts and published), newest first
     * by {@code versionNumber}. Read-only history/listing support for the admin console - no
     * update or delete capability is added.
     */
    List<PolicyVersion> findAllOrderByVersionNumberDesc();

    /**
     * Slice 1: PolicyVersions filtered by publication state, newest first by
     * {@code versionNumber}.
     */
    List<PolicyVersion> findByPublishedOrderByVersionNumberDesc(boolean published);
}
