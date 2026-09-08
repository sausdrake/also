package com.example.autoauction.policy.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Slice 1b-1: minimal immutable pricing-policy snapshot. A PolicyVersion is created already
 * complete (no draft-editing workflow - the Slice 1b pre-implementation audit found no evidence
 * that pricing requires one) and transitions exactly once, from unpublished to published, via
 * {@link #publish()}. Once published, every field is intended to remain fixed forever -
 * enforced here by exposing no setters at all (reconstruction from persistence uses the
 * full-args constructor below, never a setter), and backed by a database-level trigger
 * rejecting UPDATE/DELETE on published rows (see the Slice 1b-1 migration).
 *
 * Deliberately contains only the pricing-policy field this slice's dependency graph actually
 * needs (commissionRate) - no seller-violation catalogue, no auction reference. Those belong to
 * later slices (Slice 1b-2 and beyond).
 */
public class PolicyVersion {

    private Long id;
    private Long versionNumber;
    private final BigDecimal commissionRate;
    private boolean published;
    private final OffsetDateTime effectiveFrom;
    private final Long createdBy;
    private final OffsetDateTime createdAt;

    /**
     * Creates a new, not-yet-persisted, unpublished PolicyVersion using the system clock.
     */
    public PolicyVersion(BigDecimal commissionRate, Long createdBy) {
        this(commissionRate, createdBy, Clock.systemUTC());
    }

    /**
     * Clock-aware overload, for deterministic tests. {@code effectiveFrom} and
     * {@code createdAt} are both stamped at creation time - this slice does not support
     * future-dating a policy's effectiveness (no scheduling functionality is introduced here;
     * see the Slice 1b pre-implementation audit, section 9).
     */
    public PolicyVersion(BigDecimal commissionRate, Long createdBy, Clock clock) {
        Objects.requireNonNull(commissionRate, "Комиссия не может быть null");
        Objects.requireNonNull(createdBy, "ID создателя не может быть null");
        if (commissionRate.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Комиссия не может быть отрицательной: " + commissionRate);
        }
        if (commissionRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("Комиссия должна быть меньше 100% (1.0): " + commissionRate);
        }
        this.commissionRate = commissionRate;
        this.createdBy = createdBy;
        OffsetDateTime now = OffsetDateTime.now(clock);
        this.effectiveFrom = now;
        this.createdAt = now;
        this.published = false;
    }

    /**
     * Reconstruction constructor, used exclusively by the persistence mapper to rebuild a
     * PolicyVersion already known to the database. Never used to create a new policy - that is
     * the job of the public constructors above, which enforce the domain's own validation.
     */
    public PolicyVersion(Long id, Long versionNumber, BigDecimal commissionRate, boolean published,
                          OffsetDateTime effectiveFrom, Long createdBy, OffsetDateTime createdAt) {
        this.id = id;
        this.versionNumber = versionNumber;
        this.commissionRate = commissionRate;
        this.published = published;
        this.effectiveFrom = effectiveFrom;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    /**
     * The one and only legitimate state transition. Never allows published -> unpublished, and
     * never allows publishing twice. Concurrency safety for the actual persisted transition is
     * the repository's responsibility (an atomic claim, not this in-memory check) - this method
     * documents and enforces the business rule for any single, already-loaded instance.
     */
    public void publish() {
        if (this.published) {
            throw new IllegalStateException("PolicyVersion уже опубликована и не может быть опубликована повторно");
        }
        this.published = true;
    }

    public Long getId() { return id; }
    public Long getVersionNumber() { return versionNumber; }
    public BigDecimal getCommissionRate() { return commissionRate; }
    public boolean isPublished() { return published; }
    public OffsetDateTime getEffectiveFrom() { return effectiveFrom; }
    public Long getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
