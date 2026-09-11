package com.example.autoauction.settlement.infrastructure.persistence;

import com.example.autoauction.settlement.domain.SettlementCorrectionType;
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
 * Step 2C: JPA representation of {@code settlement_correction} (V44). {@code unique = true} on
 * {@code idempotency_key} mirrors {@code deposit_transactions.idempotency_key}'s (V21) own
 * established H2/PostgreSQL-equivalence reasoning.
 */
@Entity
@Table(name = "settlement_correction",
        uniqueConstraints = @UniqueConstraint(name = "uk_settlement_correction_idempotency_key",
                columnNames = "idempotency_key"))
public class JpaSettlementCorrectionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "correction_type", nullable = false, length = 24)
    private SettlementCorrectionType correctionType;

    @Column(name = "corrected_amount", precision = 19, scale = 2)
    private BigDecimal correctedAmount;

    @Column(name = "corrected_seller_id")
    private Long correctedSellerId;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Column(name = "corrected_by_admin_id", nullable = false)
    private Long correctedByAdminId;

    @Column(name = "corrected_at", nullable = false)
    private OffsetDateTime correctedAt;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    protected JpaSettlementCorrectionEntity() {
    }

    public JpaSettlementCorrectionEntity(Long id, Long settlementId, SettlementCorrectionType correctionType,
                                         BigDecimal correctedAmount, Long correctedSellerId, String reason,
                                         Long correctedByAdminId, OffsetDateTime correctedAt, String idempotencyKey) {
        this.id = id;
        this.settlementId = settlementId;
        this.correctionType = correctionType;
        this.correctedAmount = correctedAmount;
        this.correctedSellerId = correctedSellerId;
        this.reason = reason;
        this.correctedByAdminId = correctedByAdminId;
        this.correctedAt = correctedAt;
        this.idempotencyKey = idempotencyKey;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSettlementId() { return settlementId; }
    public void setSettlementId(Long settlementId) { this.settlementId = settlementId; }
    public SettlementCorrectionType getCorrectionType() { return correctionType; }
    public void setCorrectionType(SettlementCorrectionType correctionType) { this.correctionType = correctionType; }
    public BigDecimal getCorrectedAmount() { return correctedAmount; }
    public void setCorrectedAmount(BigDecimal correctedAmount) { this.correctedAmount = correctedAmount; }
    public Long getCorrectedSellerId() { return correctedSellerId; }
    public void setCorrectedSellerId(Long correctedSellerId) { this.correctedSellerId = correctedSellerId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getCorrectedByAdminId() { return correctedByAdminId; }
    public void setCorrectedByAdminId(Long correctedByAdminId) { this.correctedByAdminId = correctedByAdminId; }
    public OffsetDateTime getCorrectedAt() { return correctedAt; }
    public void setCorrectedAt(OffsetDateTime correctedAt) { this.correctedAt = correctedAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
