// deposit/domain/FrozenDeposit.java
package com.example.autoauction.deposit.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class FrozenDeposit {
    private Long auctionId;
    private BigDecimal amount;
    private FreezeReason reason;
    private boolean winner;
    private OffsetDateTime frozenAt;

    // Конструктор с FreezeReason (без точки!)
    public FrozenDeposit(Long auctionId, BigDecimal amount, FreezeReason reason) {
        this.auctionId = auctionId;
        this.amount = amount;
        this.reason = reason;
        this.winner = false;
        this.frozenAt = OffsetDateTime.now();
    }

    // Геттеры
    public Long getAuctionId() { return auctionId; }
    public BigDecimal getAmount() { return amount; }
    public FreezeReason getReason() { return reason; }
    public boolean isWinner() { return winner; }
    public OffsetDateTime getFrozenAt() { return frozenAt; }

    // Сеттер для amount (если нужен)
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public void markAsWinner() { this.winner = true; }
}