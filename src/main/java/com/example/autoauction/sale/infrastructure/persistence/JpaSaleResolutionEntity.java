package com.example.autoauction.sale.infrastructure.persistence;

import com.example.autoauction.sale.domain.FinancialResolutionStatus;
import com.example.autoauction.sale.domain.SaleResolutionResult;
import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.time.OffsetDateTime;

/**
 * auction_id/payment_evidence_id/handover_evidence_id are plain Long fields, not
 * {@code @ManyToOne}/{@code @JoinColumn} - same convention and reasoning as
 * JpaPaymentEvidenceEntity/JpaSellerDiagnosticCommitmentEntity. All three real FKs live in
 * V34's migration SQL and are provable only against the actual Flyway-migrated schema. The
 * UNIQUE(auction_id) and both CHECK constraints below ARE mirrored at the JPA-annotation level
 * and are therefore exercised under this project's H2 test profile.
 */
@Entity
@Table(name = "sale_resolution")
@Check(constraints = "result <> 'SUCCESS' OR (payment_evidence_id IS NOT NULL AND handover_evidence_id IS NOT NULL)")
public class JpaSaleResolutionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "auction_id", nullable = false, unique = true)
    private Long auctionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 20)
    private SaleResolutionResult result;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "resolved_by", nullable = false)
    private Long resolvedBy;

    @Column(name = "resolved_at", nullable = false)
    private OffsetDateTime resolvedAt;

    @Column(name = "payment_evidence_id")
    private Long paymentEvidenceId;

    @Column(name = "handover_evidence_id")
    private Long handoverEvidenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "financial_resolution_status", nullable = false, length = 20)
    private FinancialResolutionStatus financialResolutionStatus;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected JpaSaleResolutionEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public SaleResolutionResult getResult() { return result; }
    public void setResult(SaleResolutionResult result) { this.result = result; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(Long resolvedBy) { this.resolvedBy = resolvedBy; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
    public Long getPaymentEvidenceId() { return paymentEvidenceId; }
    public void setPaymentEvidenceId(Long paymentEvidenceId) { this.paymentEvidenceId = paymentEvidenceId; }
    public Long getHandoverEvidenceId() { return handoverEvidenceId; }
    public void setHandoverEvidenceId(Long handoverEvidenceId) { this.handoverEvidenceId = handoverEvidenceId; }
    public FinancialResolutionStatus getFinancialResolutionStatus() { return financialResolutionStatus; }
    public void setFinancialResolutionStatus(FinancialResolutionStatus financialResolutionStatus) { this.financialResolutionStatus = financialResolutionStatus; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
