package com.example.autoauction.diagnostic.commitment.infrastructure.persistence;

import com.example.autoauction.diagnostic.commitment.domain.TriggerSource;
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
@Table(name = "seller_violation_resolution",
        uniqueConstraints = @UniqueConstraint(name = "uk_seller_violation_resolution_commitment",
                columnNames = "seller_diagnostic_commitment_id"))
public class JpaSellerViolationResolutionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seller_diagnostic_commitment_id", nullable = false)
    private Long sellerDiagnosticCommitmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_source", nullable = false, length = 20)
    private TriggerSource triggerSource;

    @Column(name = "auction_id")
    private Long auctionId;

    @Column(name = "policy_version_id", nullable = false)
    private Long policyVersionId;

    @Column(name = "raw_penalty_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal rawPenaltyAmount;

    @Column(name = "applied_penalty_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal appliedPenaltyAmount;

    @Column(name = "refund_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "resolved_by", nullable = false)
    private Long resolvedBy;

    @Column(name = "resolved_at", nullable = false)
    private OffsetDateTime resolvedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected JpaSellerViolationResolutionEntity() {}

    public JpaSellerViolationResolutionEntity(Long sellerDiagnosticCommitmentId, TriggerSource triggerSource,
                                               Long auctionId, Long policyVersionId, BigDecimal rawPenaltyAmount,
                                               BigDecimal appliedPenaltyAmount, BigDecimal refundAmount,
                                               Long resolvedBy, OffsetDateTime resolvedAt, OffsetDateTime createdAt) {
        this.sellerDiagnosticCommitmentId = sellerDiagnosticCommitmentId;
        this.triggerSource = triggerSource;
        this.auctionId = auctionId;
        this.policyVersionId = policyVersionId;
        this.rawPenaltyAmount = rawPenaltyAmount;
        this.appliedPenaltyAmount = appliedPenaltyAmount;
        this.refundAmount = refundAmount;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = resolvedAt;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSellerDiagnosticCommitmentId() { return sellerDiagnosticCommitmentId; }
    public TriggerSource getTriggerSource() { return triggerSource; }
    public Long getAuctionId() { return auctionId; }
    public Long getPolicyVersionId() { return policyVersionId; }
    public BigDecimal getRawPenaltyAmount() { return rawPenaltyAmount; }
    public BigDecimal getAppliedPenaltyAmount() { return appliedPenaltyAmount; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public Long getResolvedBy() { return resolvedBy; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
