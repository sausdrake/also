package com.example.autoauction.policy.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Slice 1b-1: minimal immutable pricing-policy snapshot. A PolicyVersion is created already
 * complete (no draft-editing workflow - the Slice 1b pre-implementation audit found no evidence
 * that pricing requires one) and transitions exactly once, from unpublished to published, via
 * {@link #publish}. Once published, every field is intended to remain fixed forever -
 * enforced here by exposing no setters at all (reconstruction from persistence uses the
 * full-args constructor below, never a setter), and backed by a database-level trigger
 * rejecting UPDATE/DELETE on published rows (see the Slice 1b-1 migration).
 *
 * <p>Policy Administration concurrency lock (locked design): extends this snapshot with
 * publication metadata ({@link #publishedBy}, {@link #publishedAt}), immutable draft lineage
 * ({@link #basedOnPublishedVersionId}, captured once at construction and never mutated
 * afterwards), an entity-level optimistic-lock column ({@link #version}, Boundary A - protects
 * this row's own fields from a lost update), and a separate aggregate-configuration counter
 * ({@link #configurationRevision}, Boundary C - protects the checker's "I reviewed exactly this
 * complete configuration" freshness guarantee; deliberately NOT the same column as
 * {@link #version} - see the locked concurrency design, section A). {@code effectiveFrom} has
 * been removed entirely (Decision 4).
 */
public class PolicyVersion {

    /**
     * Reserved actor id used ONLY by the automatic startup seed of the very first published
     * PolicyVersion (see {@code policy.application.PolicyBootstrap}). {@code
     * policy_version.created_by} has no foreign key (V32), and {@code BIGSERIAL} user ids start
     * at 1, so 0 collides with no real user. Every admin-authored PolicyVersion created
     * afterwards records the real authenticated admin's id.
     */
    public static final Long SYSTEM_ACTOR_ID = 0L;

    /**
     * Reserved actor id used ONLY by {@code PolicyBootstrap} as the PUBLISHING actor for the
     * very first PolicyVersion - distinct from {@link #SYSTEM_ACTOR_ID} (the creating actor for
     * that same seed) purely so the locked maker/checker rule ("draft creator cannot publish own
     * draft") is satisfied mechanically for this one system-internal, no-human-involved startup
     * action, without fabricating a second human admin. Never used for any admin-originated
     * publication - those always carry the real authenticated admin's id as publisher, which is
     * checked against the real creator's id.
     */
    public static final Long SYSTEM_BOOTSTRAP_PUBLISHER_ID = -1L;

    private Long id;
    private Long versionNumber;
    private BigDecimal commissionRate;
    private boolean published;
    private final Long createdBy;
    private final OffsetDateTime createdAt;
    private Long publishedBy;
    private OffsetDateTime publishedAt;
    private final Long basedOnPublishedVersionId;
    private Long version;
    private Long configurationRevision;

    /**
     * Creates a new, not-yet-persisted, unpublished PolicyVersion using the system clock.
     *
     * @param basedOnPublishedVersionId the id of the PolicyVersion that was current-published at
     *                                  the moment this draft was created, or {@code null} if none
     *                                  had ever been published yet. Captured once, here, and
     *                                  never mutated afterwards (immutable lineage, Decision 5).
     */
    public PolicyVersion(BigDecimal commissionRate, Long createdBy, Long basedOnPublishedVersionId) {
        this(commissionRate, createdBy, basedOnPublishedVersionId, Clock.systemUTC());
    }

    /**
     * Clock-aware overload, for deterministic tests.
     */
    public PolicyVersion(BigDecimal commissionRate, Long createdBy, Long basedOnPublishedVersionId, Clock clock) {
        Objects.requireNonNull(createdBy, "ID создателя не может быть null");
        validateCommissionRate(commissionRate);
        this.commissionRate = commissionRate;
        this.createdBy = createdBy;
        this.basedOnPublishedVersionId = basedOnPublishedVersionId;
        this.createdAt = OffsetDateTime.now(clock);
        this.published = false;
        this.publishedBy = null;
        this.publishedAt = null;
        this.version = 0L;
        this.configurationRevision = 0L;
    }

    /**
     * Reconstruction constructor, used exclusively by the persistence mapper to rebuild a
     * PolicyVersion already known to the database. Never used to create a new policy - that is
     * the job of the public constructors above, which enforce the domain's own validation.
     */
    public PolicyVersion(Long id, Long versionNumber, BigDecimal commissionRate, boolean published,
                          Long createdBy, OffsetDateTime createdAt, Long publishedBy, OffsetDateTime publishedAt,
                          Long basedOnPublishedVersionId, Long version, Long configurationRevision) {
        this.id = id;
        this.versionNumber = versionNumber;
        this.commissionRate = commissionRate;
        this.published = published;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.publishedBy = publishedBy;
        this.publishedAt = publishedAt;
        this.basedOnPublishedVersionId = basedOnPublishedVersionId;
        this.version = version;
        this.configurationRevision = configurationRevision;
    }

    /**
     * The one and only legitimate in-memory state transition, documenting and enforcing the
     * business rule for any single, already-loaded instance. Never allows published ->
     * unpublished, and never allows publishing twice.
     *
     * <p>Concurrency safety for the actual persisted transition is the repository's
     * responsibility (an atomic, condition-checked claim - see
     * {@code PolicyVersionRepository.claimPublish}, which also sets the same publication
     * metadata atomically in the same SQL statement), not this method - the real publish flow
     * never loads-mutates-saves through this method, it calls the atomic claim directly. This
     * method exists purely to keep the in-memory domain invariant expressible and testable.
     */
    public void publish(Long publishedBy, OffsetDateTime publishedAt) {
        if (this.published) {
            throw new IllegalStateException("PolicyVersion уже опубликована и не может быть опубликована повторно");
        }
        Objects.requireNonNull(publishedBy, "ID публикующего не может быть null");
        Objects.requireNonNull(publishedAt, "Дата публикации не может быть null");
        this.published = true;
        this.publishedBy = publishedBy;
        this.publishedAt = publishedAt;
    }

    /**
     * Slice 1 (Policy Administration): the only legitimate way to change a draft's commission
     * rate. Mirrors the draft-only, published-immutable contract of the seller-violation
     * configuration path. Never a public setter, never reflection - a published PolicyVersion
     * is immutable and this is rejected for one.
     *
     * @throws IllegalStateException    if this PolicyVersion is already published
     * @throws NullPointerException     if {@code newRate} is null
     * @throws IllegalArgumentException if {@code newRate} is &lt; 0 or &gt;= 1
     */
    public void changeCommissionRate(BigDecimal newRate) {
        if (this.published) {
            throw new IllegalStateException(
                    "PolicyVersion опубликована и неизменяема - ставку комиссии изменить нельзя");
        }
        validateCommissionRate(newRate);
        this.commissionRate = newRate;
    }

    private static void validateCommissionRate(BigDecimal rate) {
        Objects.requireNonNull(rate, "Комиссия не может быть null");
        if (rate.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Комиссия не может быть отрицательной: " + rate);
        }
        if (rate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("Комиссия должна быть меньше 100% (1.0): " + rate);
        }
    }

    public Long getId() { return id; }
    public Long getVersionNumber() { return versionNumber; }
    public BigDecimal getCommissionRate() { return commissionRate; }
    public boolean isPublished() { return published; }
    public Long getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public Long getPublishedBy() { return publishedBy; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public Long getBasedOnPublishedVersionId() { return basedOnPublishedVersionId; }
    public Long getVersion() { return version; }
    public Long getConfigurationRevision() { return configurationRevision; }
}
