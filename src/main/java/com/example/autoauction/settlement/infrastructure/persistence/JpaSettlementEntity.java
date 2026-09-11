package com.example.autoauction.settlement.infrastructure.persistence;

import com.example.autoauction.settlement.domain.SettlementStatus;
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

/**
 * Step 2C: JPA representation of {@code settlement} (V44). {@code unique = true} on
 * {@code auction_id} drives Hibernate's own ddl-auto schema generation for the H2 test profile,
 * mirroring the same reasoning already applied to {@code payment.merchant_reference} /
 * {@code sale_resolution.auction_id}. Flyway remains the sole production schema authority.
 */
@Entity
@Table(name = "settlement",
        uniqueConstraints = @UniqueConstraint(name = "uk_settlement_auction_id", columnNames = "auction_id"))
public class JpaSettlementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "auction_id", nullable = false, unique = true)
    private Long auctionId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "expected_buyer_paid_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal expectedBuyerPaidAmount;

    @Column(name = "expected_seller_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal expectedSellerAmount;

    @Column(name = "expected_commission", nullable = false, precision = 19, scale = 2)
    private BigDecimal expectedCommission;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SettlementStatus status;

    @Column(name = "attested_payout_amount", precision = 19, scale = 2)
    private BigDecimal attestedPayoutAmount;

    @Column(name = "recorded_by_admin_id")
    private Long recordedByAdminId;

    @Column(name = "recorded_at")
    private OffsetDateTime recordedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected JpaSettlementEntity() {
    }

    public JpaSettlementEntity(Long id, Long auctionId, Long sellerId, BigDecimal expectedBuyerPaidAmount,
                               BigDecimal expectedSellerAmount, BigDecimal expectedCommission,
                               SettlementStatus status, BigDecimal attestedPayoutAmount,
                               Long recordedByAdminId, OffsetDateTime recordedAt, OffsetDateTime createdAt) {
        this.id = id;
        this.auctionId = auctionId;
        this.sellerId = sellerId;
        this.expectedBuyerPaidAmount = expectedBuyerPaidAmount;
        this.expectedSellerAmount = expectedSellerAmount;
        this.expectedCommission = expectedCommission;
        this.status = status;
        this.attestedPayoutAmount = attestedPayoutAmount;
        this.recordedByAdminId = recordedByAdminId;
        this.recordedAt = recordedAt;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public Long getSellerId() { return sellerId; }
    public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
    public BigDecimal getExpectedBuyerPaidAmount() { return expectedBuyerPaidAmount; }
    public void setExpectedBuyerPaidAmount(BigDecimal expectedBuyerPaidAmount) { this.expectedBuyerPaidAmount = expectedBuyerPaidAmount; }
    public BigDecimal getExpectedSellerAmount() { return expectedSellerAmount; }
    public void setExpectedSellerAmount(BigDecimal expectedSellerAmount) { this.expectedSellerAmount = expectedSellerAmount; }
    public BigDecimal getExpectedCommission() { return expectedCommission; }
    public void setExpectedCommission(BigDecimal expectedCommission) { this.expectedCommission = expectedCommission; }
    public SettlementStatus getStatus() { return status; }
    public void setStatus(SettlementStatus status) { this.status = status; }
    public BigDecimal getAttestedPayoutAmount() { return attestedPayoutAmount; }
    public void setAttestedPayoutAmount(BigDecimal attestedPayoutAmount) { this.attestedPayoutAmount = attestedPayoutAmount; }
    public Long getRecordedByAdminId() { return recordedByAdminId; }
    public void setRecordedByAdminId(Long recordedByAdminId) { this.recordedByAdminId = recordedByAdminId; }
    public OffsetDateTime getRecordedAt() { return recordedAt; }
    public void setRecordedAt(OffsetDateTime recordedAt) { this.recordedAt = recordedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
