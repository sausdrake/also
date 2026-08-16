package com.example.autoauction.deposit.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class Deposit {
    private Long id;
    private Long userId;
    private BigDecimal balance;
    private BigDecimal blockedAmount;
    private DepositStatus status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Long version;

    public Deposit(Long userId, BigDecimal initialBalance) {
        this.userId = userId;
        this.balance = initialBalance != null ? initialBalance : BigDecimal.ZERO;
        this.blockedAmount = BigDecimal.ZERO;
        this.status = DepositStatus.ACTIVE;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
        this.version = 0L;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public BigDecimal getBalance() { return balance; }
    public BigDecimal getBlockedAmount() { return blockedAmount; }
    public DepositStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }

    public void setId(Long id) { this.id = id; }
    public void setUserId(Long userId) { this.userId = userId; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public void setBlockedAmount(BigDecimal blockedAmount) { this.blockedAmount = blockedAmount; }
    public void setStatus(DepositStatus status) { this.status = status; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public void setVersion(Long version) { this.version = version; }

    public BigDecimal getAvailableBalance() {
        return balance.subtract(blockedAmount);
    }

    public void freezeForAuction(BigDecimal requiredAmount) {
        if (requiredAmount == null || requiredAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Некорректная сумма блокировки: " + requiredAmount);
        }

        if (getAvailableBalance().compareTo(requiredAmount) < 0) {
            throw new IllegalStateException(
                    String.format("Недостаточно средств. Доступно: %s, требуется: %s",
                            getAvailableBalance(), requiredAmount)
            );
        }

        this.blockedAmount = this.blockedAmount.add(requiredAmount);
        this.updatedAt = OffsetDateTime.now();
    }

    public void unfreezeForAuction(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Некорректная сумма разблокировки: " + amount);
        }

        if (this.blockedAmount.compareTo(amount) < 0) {
            throw new IllegalStateException(
                    String.format("Недостаточно заблокированных средств. Заблокировано: %s, запрошено: %s",
                            this.blockedAmount, amount)
            );
        }

        this.blockedAmount = this.blockedAmount.subtract(amount);
        this.updatedAt = OffsetDateTime.now();
    }

    public void deposit(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Сумма депозита должна быть больше 0");
        }
        this.balance = this.balance.add(amount);
        this.updatedAt = OffsetDateTime.now();
    }

    public void withdraw(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Сумма вывода должна быть больше 0");
        }
        if (getAvailableBalance().compareTo(amount) < 0) {
            throw new IllegalStateException(
                    String.format("Недостаточно доступных средств. Доступно: %s, запрошено: %s",
                            getAvailableBalance(), amount)
            );
        }
        this.balance = this.balance.subtract(amount);
        this.updatedAt = OffsetDateTime.now();
    }

    public void unfreezeAll() {
        this.blockedAmount = BigDecimal.ZERO;
        this.updatedAt = OffsetDateTime.now();
    }

    @Override
    public String toString() {
        return String.format("Deposit{id=%d, userId=%d, balance=%s, blockedAmount=%s, available=%s, status=%s}",
                id, userId, balance, blockedAmount, getAvailableBalance(), status);
    }
}