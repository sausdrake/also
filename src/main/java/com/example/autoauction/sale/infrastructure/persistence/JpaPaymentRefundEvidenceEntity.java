package com.example.autoauction.sale.infrastructure.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Slice 1: mirrors {@link JpaPaymentEvidenceEntity}'s conventions exactly.
 *
 * {@code payment_evidence_id} and {@code auction_id} are plain Longs, not
 * {@code @ManyToOne}/{@code @JoinColumn} - matching the established convention (see
 * JpaPaymentEvidenceEntity's own Javadoc for the identical reasoning). The real FKs to
 * {@code payment_evidence(id)} and {@code auctions(id)} are declared in
 * V41__payment_refund_evidence.sql and are provable only against the actual Flyway-migrated
 * (Postgres) schema, not under this project's test profile (ddl-auto=create-drop, Flyway
 * disabled). The two UNIQUE constraints and the amount CHECK below ARE mirrored at the
 * JPA-annotation level (via {@code unique = true} and {@code @Check}) because Hibernate
 * auto-creates them under H2 from these annotations.
 */
@Entity
@Table(name = "payment_refund_evidence")
@Check(constraints = "refunded_amount > 0")
public class JpaPaymentRefundEvidenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_evidence_id", nullable = false, unique = true)
    private Long paymentEvidenceId;

    @Column(name = "auction_id", nullable = false, unique = true)
    private Long auctionId;

    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;

    @Column(name = "refunded_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal refundedAmount;

    @Column(name = "refunded_by_admin_id", nullable = false)
    private Long refundedByAdminId;

    @Column(name = "refunded_at", nullable = false)
    private OffsetDateTime refundedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected JpaPaymentRefundEvidenceEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPaymentEvidenceId() { return paymentEvidenceId; }
    public void setPaymentEvidenceId(Long paymentEvidenceId) { this.paymentEvidenceId = paymentEvidenceId; }
    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public Long getBuyerId() { return buyerId; }
    public void setBuyerId(Long buyerId) { this.buyerId = buyerId; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public void setRefundedAmount(BigDecimal refundedAmount) { this.refundedAmount = refundedAmount; }
    public Long getRefundedByAdminId() { return refundedByAdminId; }
    public void setRefundedByAdminId(Long refundedByAdminId) { this.refundedByAdminId = refundedByAdminId; }
    public OffsetDateTime getRefundedAt() { return refundedAt; }
    public void setRefundedAt(OffsetDateTime refundedAt) { this.refundedAt = refundedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
