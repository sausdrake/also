package com.example.autoauction.diagnostic.commitment.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * X3-E: one violation's own, UNCAPPED audit line within a {@link SellerViolationResolution}.
 * {@code calculatedPenaltyAmount} is always this violation's raw, individually-rounded
 * penalty (HALF_UP, scale 2) - never adjusted for the aggregate cap. Cap allocation among
 * individual violations is explicitly not represented anywhere (locked X3-E §6: "do not
 * allocate the cap among individual violations... individual raw calculations remain
 * auditable") - only the parent resolution's own appliedPenaltyAmount reflects the cap.
 *
 * configuredPenaltyType/configuredPenaltyValue are snapshotted from the PolicyVersionViolation
 * actually used, at resolution time - PolicyVersion is already immutable once published so
 * this can never drift, but snapshotting explicitly removes any need for a future reader to
 * re-join back to policy_version_violation to know what was applied.
 *
 * Immutable after construction - no setters.
 *
 * {@code sellerViolationResolutionId} may be null at construction time (the header's id is
 * not yet known until it is itself persisted) - the persistence adapter wires the real,
 * generated header id into the JPA entity at save time, independent of whatever value this
 * in-memory domain object carries. Every other field is required.
 */
public class SellerViolationResolutionItem {

    private Long id;
    private final Long sellerViolationResolutionId;
    private final Long violationDefinitionId;
    private final com.example.autoauction.policy.domain.PenaltyType configuredPenaltyType;
    private final BigDecimal configuredPenaltyValue;
    private final BigDecimal calculatedPenaltyAmount;
    private final OffsetDateTime createdAt;

    public SellerViolationResolutionItem(Long sellerViolationResolutionId, Long violationDefinitionId,
                                          com.example.autoauction.policy.domain.PenaltyType configuredPenaltyType,
                                          BigDecimal configuredPenaltyValue, BigDecimal calculatedPenaltyAmount,
                                          OffsetDateTime createdAt) {
        Objects.requireNonNull(violationDefinitionId, "violationDefinitionId не может быть null");
        Objects.requireNonNull(configuredPenaltyType, "configuredPenaltyType не может быть null");
        Objects.requireNonNull(configuredPenaltyValue, "configuredPenaltyValue не может быть null");
        Objects.requireNonNull(calculatedPenaltyAmount, "calculatedPenaltyAmount не может быть null");
        Objects.requireNonNull(createdAt, "createdAt не может быть null");
        if (calculatedPenaltyAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(
                    "calculatedPenaltyAmount не может быть отрицательным: " + calculatedPenaltyAmount);
        }
        this.sellerViolationResolutionId = sellerViolationResolutionId;
        this.violationDefinitionId = violationDefinitionId;
        this.configuredPenaltyType = configuredPenaltyType;
        this.configuredPenaltyValue = configuredPenaltyValue;
        this.calculatedPenaltyAmount = calculatedPenaltyAmount;
        this.createdAt = createdAt;
    }

    /**
     * Reconstruction constructor, used exclusively by the persistence mapper.
     */
    public SellerViolationResolutionItem(Long id, Long sellerViolationResolutionId, Long violationDefinitionId,
                                          com.example.autoauction.policy.domain.PenaltyType configuredPenaltyType,
                                          BigDecimal configuredPenaltyValue, BigDecimal calculatedPenaltyAmount,
                                          OffsetDateTime createdAt) {
        this(sellerViolationResolutionId, violationDefinitionId, configuredPenaltyType, configuredPenaltyValue,
                calculatedPenaltyAmount, createdAt);
        this.id = id;
    }

    public Long getId() { return id; }
    public Long getSellerViolationResolutionId() { return sellerViolationResolutionId; }
    public Long getViolationDefinitionId() { return violationDefinitionId; }
    public com.example.autoauction.policy.domain.PenaltyType getConfiguredPenaltyType() { return configuredPenaltyType; }
    public BigDecimal getConfiguredPenaltyValue() { return configuredPenaltyValue; }
    public BigDecimal getCalculatedPenaltyAmount() { return calculatedPenaltyAmount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
