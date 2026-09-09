package com.example.autoauction.policy.infrastructure.persistence;

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
@Table(name = "policy_version_violation",
        uniqueConstraints = @UniqueConstraint(name = "uk_policy_version_violation",
                columnNames = {"policy_version_id", "violation_definition_id"}))
public class JpaPolicyVersionViolationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_version_id", nullable = false)
    private Long policyVersionId;

    @Column(name = "violation_definition_id", nullable = false)
    private Long violationDefinitionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "penalty_type", nullable = false, length = 20)
    private PenaltyType penaltyType;

    @Column(name = "penalty_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal penaltyValue;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected JpaPolicyVersionViolationEntity() {}

    public JpaPolicyVersionViolationEntity(Long policyVersionId, Long violationDefinitionId,
                                            PenaltyType penaltyType, BigDecimal penaltyValue, OffsetDateTime createdAt) {
        this.policyVersionId = policyVersionId;
        this.violationDefinitionId = violationDefinitionId;
        this.penaltyType = penaltyType;
        this.penaltyValue = penaltyValue;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPolicyVersionId() { return policyVersionId; }
    public void setPolicyVersionId(Long policyVersionId) { this.policyVersionId = policyVersionId; }
    public Long getViolationDefinitionId() { return violationDefinitionId; }
    public void setViolationDefinitionId(Long violationDefinitionId) { this.violationDefinitionId = violationDefinitionId; }
    public PenaltyType getPenaltyType() { return penaltyType; }
    public void setPenaltyType(PenaltyType penaltyType) { this.penaltyType = penaltyType; }
    public BigDecimal getPenaltyValue() { return penaltyValue; }
    public void setPenaltyValue(BigDecimal penaltyValue) { this.penaltyValue = penaltyValue; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
