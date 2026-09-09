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

    /**
     * X3-A (SALE_FAILED outcome BUYER_FAULT): permanently confiscates an already-frozen amount
     * to the platform. Unlike {@link #unfreezeForAuction}, which only reduces blockedAmount
     * (the money stays in balance, becoming available again), this reduces BOTH blockedAmount
     * AND balance - the money actually leaves the account, it does not return to the buyer.
     * Deliberately not implemented via {@link #withdraw}, which checks availableBalance - the
     * wrong gate here, since the amount being removed is specifically the frozen portion, not
     * the available portion.
     */
    public void confiscateFrozen(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Некорректная сумма конфискации: " + amount);
        }

        if (this.blockedAmount.compareTo(amount) < 0) {
            throw new IllegalStateException(
                    String.format("Недостаточно заблокированных средств для конфискации. Заблокировано: %s, требуется: %s",
                            this.blockedAmount, amount)
            );
        }

        this.blockedAmount = this.blockedAmount.subtract(amount);
        this.balance = this.balance.subtract(amount);
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

    /**
     * PHASE 3B: releases a fully-frozen amount (e.g. a seller diagnostic commitment),
     * permanently deducting {@code chargeAmount} of it (a fee/penalty) from {@code balance}
     * while returning the remainder ({@code totalFrozen - chargeAmount}) to
     * {@code availableBalance} - a single combined mutation, not a separate unfreeze-then-
     * withdraw pair, so no intermediate state is ever visible to a concurrent transaction.
     * {@code chargeAmount == 0} is valid (nothing charged, full amount returned).
     */
    public void chargeFromFrozen(BigDecimal totalFrozen, BigDecimal chargeAmount) {
        if (totalFrozen == null || totalFrozen.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Некорректная сумма для снятия заморозки: " + totalFrozen);
        }
        if (chargeAmount == null || chargeAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Некорректная сумма удержания: " + chargeAmount);
        }
        if (chargeAmount.compareTo(totalFrozen) > 0) {
            throw new IllegalArgumentException(
                    String.format("Сумма удержания не может превышать замороженную сумму. Удержание: %s, заморожено: %s",
                            chargeAmount, totalFrozen));
        }
        if (this.blockedAmount.compareTo(totalFrozen) < 0) {
            throw new IllegalStateException(
                    String.format("Недостаточно заблокированных средств. Заблокировано: %s, требуется освободить: %s",
                            this.blockedAmount, totalFrozen));
        }

        this.blockedAmount = this.blockedAmount.subtract(totalFrozen);
        this.balance = this.balance.subtract(chargeAmount);
        this.updatedAt = OffsetDateTime.now();
    }

    @Override
    public String toString() {
        return String.format("Deposit{id=%d, userId=%d, balance=%s, blockedAmount=%s, available=%s, status=%s}",
                id, userId, balance, blockedAmount, getAvailableBalance(), status);
    }
}