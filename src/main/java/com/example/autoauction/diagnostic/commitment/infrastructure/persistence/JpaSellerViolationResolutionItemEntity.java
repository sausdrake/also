package com.example.autoauction.diagnostic.commitment.infrastructure.persistence;

import com.example.autoauction.policy.domain.PenaltyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "seller_violation_resolution_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_seller_violation_resolution_item",
                columnNames = {"seller_violation_resolution_id", "violation_definition_id"}))
public class JpaSellerViolationResolutionItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_violation_resolution_id", nullable = false)
    private Long sellerViolationResolutionId;

    @Column(name = "violation_definition_id", nullable = false)
    private Long violationDefinitionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "configured_penalty_type", nullable = false, length = 20)
    private PenaltyType configuredPenaltyType;

    @Column(name = "configured_penalty_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal configuredPenaltyValue;

    @Column(name = "calculated_penalty_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal calculatedPenaltyAmount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected JpaSellerViolationResolutionItemEntity() {}

    public JpaSellerViolationResolutionItemEntity(Long sellerViolationResolutionId, Long violationDefinitionId,
                                                   PenaltyType configuredPenaltyType, BigDecimal configuredPenaltyValue,
                                                   BigDecimal calculatedPenaltyAmount, OffsetDateTime createdAt) {
        this.sellerViolationResolutionId = sellerViolationResolutionId;
        this.violationDefinitionId = violationDefinitionId;
        this.configuredPenaltyType = configuredPenaltyType;
        this.configuredPenaltyValue = configuredPenaltyValue;
        this.calculatedPenaltyAmount = calculatedPenaltyAmount;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSellerViolationResolutionId() { return sellerViolationResolutionId; }
    public void setSellerViolationResolutionId(Long sellerViolationResolutionId) { this.sellerViolationResolutionId = sellerViolationResolutionId; }
    public Long getViolationDefinitionId() { return violationDefinitionId; }
    public PenaltyType getConfiguredPenaltyType() { return configuredPenaltyType; }
    public BigDecimal getConfiguredPenaltyValue() { return configuredPenaltyValue; }
    public BigDecimal getCalculatedPenaltyAmount() { return calculatedPenaltyAmount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
