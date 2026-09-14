package com.example.autoauction.policy.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * X3-E: the version-specific penalty configuration for one {@link ViolationDefinition} under
 * one {@link PolicyVersion} - the "how much" side of the locked violation model (stable
 * identity + immutable version-specific penalty configuration).
 *
 * Mutable ONLY while its parent PolicyVersion is unpublished (enforced at the service layer -
 * see PolicyVersionService.updateDraftViolation - and, defense-in-depth, by the DB trigger
 * added in V38, which mirrors policy_version's own published-row immutability trigger and
 * additionally blocks DELETE to close the "delete + reinsert" bypass). Once published,
 * immutable forever - never mutated for an already-published PolicyVersion, never reused
 * across versions (a new PolicyVersion gets its OWN copied rows, see
 * PolicyVersionService.create()'s copy-forward step - never a shared row).
 *
 * penaltyValue is always 0-100 for PERCENTAGE (this module's own scale) or &gt;=0 for
 * FIXED_AMOUNT - validated here in-memory and, defense-in-depth, by V38's own CHECK
 * constraint.
 */
public class PolicyVersionViolation {

    private Long id;
    private final Long policyVersionId;
    private final Long violationDefinitionId;
    private final PenaltyType penaltyType;
    private final BigDecimal penaltyValue;
    private final OffsetDateTime createdAt;
    private Long version;

    public PolicyVersionViolation(Long policyVersionId, Long violationDefinitionId,
                                   PenaltyType penaltyType, BigDecimal penaltyValue, OffsetDateTime createdAt) {
        Objects.requireNonNull(policyVersionId, "policyVersionId не может быть null");
        Objects.requireNonNull(violationDefinitionId, "violationDefinitionId не может быть null");
        Objects.requireNonNull(penaltyType, "penaltyType не может быть null");
        Objects.requireNonNull(penaltyValue, "penaltyValue не может быть null");
        Objects.requireNonNull(createdAt, "createdAt не может быть null");
        validatePenaltyValue(penaltyType, penaltyValue);
        this.policyVersionId = policyVersionId;
        this.violationDefinitionId = violationDefinitionId;
        this.penaltyType = penaltyType;
        this.penaltyValue = penaltyValue;
        this.createdAt = createdAt;
        this.version = 0L;
    }

    /**
     * Reconstruction constructor, used by the persistence mapper and by test fixtures that do
     * not care about the optimistic-lock version (defaults to 0). Never used to create a
     * genuinely new row that is about to be inserted for the first time - see the 5-arg
     * constructor above for that; this one always carries an explicit {@code id}.
     */
    public PolicyVersionViolation(Long id, Long policyVersionId, Long violationDefinitionId,
                                   PenaltyType penaltyType, BigDecimal penaltyValue, OffsetDateTime createdAt) {
        this(policyVersionId, violationDefinitionId, penaltyType, penaltyValue, createdAt);
        this.id = id;
    }

    /**
     * Full reconstruction constructor, used exclusively by the persistence mapper to rebuild a
     * PolicyVersionViolation already known to the database, carrying its REAL persisted
     * optimistic-lock version (Boundary A) - never defaulted to 0 here, since that would silently
     * defeat the whole point of checking a caller-observed version against the real one.
     */
    public PolicyVersionViolation(Long id, Long policyVersionId, Long violationDefinitionId,
                                   PenaltyType penaltyType, BigDecimal penaltyValue, OffsetDateTime createdAt,
                                   Long version) {
        this(policyVersionId, violationDefinitionId, penaltyType, penaltyValue, createdAt);
        this.id = id;
        this.version = version;
    }

    private static void validatePenaltyValue(PenaltyType type, BigDecimal value) {
        if (type == PenaltyType.PERCENTAGE) {
            if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException(
                        "PERCENTAGE penaltyValue должен быть в диапазоне [0, 100]: " + value);
            }
        } else {
            if (value.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException(
                        "FIXED_AMOUNT penaltyValue не может быть отрицательным: " + value);
            }
        }
    }

    public Long getId() { return id; }
    public Long getPolicyVersionId() { return policyVersionId; }
    public Long getViolationDefinitionId() { return violationDefinitionId; }
    public PenaltyType getPenaltyType() { return penaltyType; }
    public BigDecimal getPenaltyValue() { return penaltyValue; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public Long getVersion() { return version; }
}
