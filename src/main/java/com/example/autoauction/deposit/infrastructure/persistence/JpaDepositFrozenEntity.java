// deposit/infrastructure/persistence/JpaDepositFrozenEntity.java
package com.example.autoauction.deposit.infrastructure.persistence;

import com.example.autoauction.deposit.domain.FreezeReason;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "deposit_frozen")
public class JpaDepositFrozenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deposit_id", nullable = false)
    private Long depositId;

    @Column(name = "auction_id", nullable = false)
    private Long auctionId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "freeze_reason", nullable = false)
    private FreezeReason freezeReason;

    @Column(name = "is_winner", nullable = false)
    private boolean isWinner = false;

    @Column(name = "frozen_at", nullable = false)
    private OffsetDateTime frozenAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected JpaDepositFrozenEntity() {}

    // Конструктор с FreezeReason
    public JpaDepositFrozenEntity(Long depositId, Long auctionId, BigDecimal amount, FreezeReason freezeReason) {
        this.depositId = depositId;
        this.auctionId = auctionId;
        this.amount = amount;
        this.freezeReason = freezeReason;
        this.frozenAt = OffsetDateTime.now();
        this.createdAt = OffsetDateTime.now();
    }

    // Геттеры
    public Long getId() { return id; }
    public Long getDepositId() { return depositId; }
    public Long getAuctionId() { return auctionId; }
    public BigDecimal getAmount() { return amount; }
    public FreezeReason getFreezeReason() { return freezeReason; }
    public boolean isWinner() { return isWinner; }
    public OffsetDateTime getFrozenAt() { return frozenAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    // Сеттеры
    public void setId(Long id) { this.id = id; }
    public void setDepositId(Long depositId) { this.depositId = depositId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public void setFreezeReason(FreezeReason freezeReason) { this.freezeReason = freezeReason; }
    public void setWinner(boolean winner) { isWinner = winner; }
    public void setFrozenAt(OffsetDateTime frozenAt) { this.frozenAt = frozenAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}