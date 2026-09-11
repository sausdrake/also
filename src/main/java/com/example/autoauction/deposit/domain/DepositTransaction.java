// Файл: deposit/domain/DepositTransaction.java

package com.example.autoauction.deposit.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class DepositTransaction {
    private Long id;
    private Long depositId;
    private Long userId;
    private Long auctionId;
    private TransactionType type;
    private BigDecimal amount;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private String reference;
    private String description;
    private String idempotencyKey;
    private OffsetDateTime createdAt;
    /** Null for every non-admin-initiated transaction (see {@link TransactionType#PAYMENT_CREDIT}
     *  and every pre-existing type); application-enforced non-null for newly created
     *  {@link TransactionType#ADMIN_ADJUSTMENT} rows only. */
    private Long performedByAdminId;

    public DepositTransaction(Long depositId, Long userId, TransactionType type,
                              BigDecimal amount, BigDecimal balanceBefore,
                              BigDecimal balanceAfter, String reference, String description) {
        this.depositId = depositId;
        this.userId = userId;
        this.type = type;
        this.amount = amount;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.reference = reference;
        this.description = description;
        this.createdAt = OffsetDateTime.now();
    }

    // Геттеры
    public Long getId() { return id; }
    public Long getDepositId() { return depositId; }
    public Long getUserId() { return userId; }
    public Long getAuctionId() { return auctionId; }
    public TransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceBefore() { return balanceBefore; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public String getReference() { return reference; }
    public String getDescription() { return description; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public Long getPerformedByAdminId() { return performedByAdminId; }

    // Сеттеры (добавляем!)
    public void setId(Long id) { this.id = id; }
    public void setDepositId(Long depositId) { this.depositId = depositId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public void setType(TransactionType type) { this.type = type; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public void setBalanceBefore(BigDecimal balanceBefore) { this.balanceBefore = balanceBefore; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }
    public void setReference(String reference) { this.reference = reference; }
    public void setDescription(String description) { this.description = description; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public void setPerformedByAdminId(Long performedByAdminId) { this.performedByAdminId = performedByAdminId; }

    public enum TransactionType {
        DEPOSIT,            // Пополнение
        WITHDRAWAL,         // Списание
        BLOCK,              // Блокировка
        UNBLOCK,            // Разблокировка
        CHARGE,             // Удержание (штраф)
        ADMIN_ADJUSTMENT,   // Ручная корректировка администратором
        /** Slice 2 · Step 2A: automatic deposit credit driven by a confirmed payment (Step 2D).
         *  Never has a human admin actor - {@code performedByAdminId} stays null. */
        PAYMENT_CREDIT
    }
}