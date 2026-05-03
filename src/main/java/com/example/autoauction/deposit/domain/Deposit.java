package com.example.autoauction.deposit.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.concurrent.ConcurrentHashMap;

public class Deposit {
    private Long id;
    private Long userId;
    private BigDecimal balance;
    private BigDecimal blockedAmount;
    private DepositStatus status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Long version;

    // Хранилище блокировок по аукционам
    private final ConcurrentHashMap<Long, FrozenDeposit> frozenByAuction = new ConcurrentHashMap<>();

    public Deposit(Long userId, BigDecimal initialBalance) {
        this.userId = userId;
        this.balance = initialBalance != null ? initialBalance : BigDecimal.ZERO;
        this.blockedAmount = BigDecimal.ZERO;
        this.status = DepositStatus.ACTIVE;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
        this.version = 0L;
    }

    // ==================== ГЕТТЕРЫ ====================

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public BigDecimal getBlockedAmount() {
        return blockedAmount;
    }

    public DepositStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public ConcurrentHashMap<Long, FrozenDeposit> getFrozenByAuction() {
        return frozenByAuction;
    }

    // ==================== СЕТТЕРЫ ====================

    public void setId(Long id) {
        this.id = id;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public void setBlockedAmount(BigDecimal blockedAmount) {
        this.blockedAmount = blockedAmount;
    }

    public void setStatus(DepositStatus status) {
        this.status = status;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    // ==================== БИЗНЕС-МЕТОДЫ ====================

    /**
     * Рассчитывает требуемую сумму депозита для участия в аукционе (1% от стартовой цены)
     */
    public static BigDecimal calculateRequiredDeposit(BigDecimal startingPrice) {
        if (startingPrice == null || startingPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return startingPrice.multiply(new BigDecimal("0.01"))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Возвращает доступный баланс (общий баланс минус заблокированные средства)
     */
    public BigDecimal getAvailableBalance() {
        return balance.subtract(blockedAmount);
    }

    /**
     * Проверяет, может ли пользователь участвовать в аукционе
     */
    public boolean canParticipateInAuction(BigDecimal auctionStartingPrice) {
        BigDecimal required = calculateRequiredDeposit(auctionStartingPrice);
        return getAvailableBalance().compareTo(required) >= 0;
    }

    /**
     * Получает сумму, заблокированную для конкретного аукциона
     */
    public BigDecimal getFrozenAmountForAuction(Long auctionId) {
        FrozenDeposit frozen = frozenByAuction.get(auctionId);
        return frozen != null ? frozen.getAmount() : null;
    }

    /**
     * Проверяет, есть ли блокировка для конкретного аукциона
     */
    public boolean hasFrozenForAuction(Long auctionId) {
        return frozenByAuction.containsKey(auctionId);
    }

    /**
     * Замораживает средства для участия в аукционе
     */
    public void freezeForAuction(Long auctionId, BigDecimal startingPrice) {
        BigDecimal requiredAmount = calculateRequiredDeposit(startingPrice);

        if (requiredAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    String.format("Некорректная сумма блокировки: %s (стартовая цена: %s)",
                            requiredAmount, startingPrice)
            );
        }

        if (getAvailableBalance().compareTo(requiredAmount) < 0) {
            throw new IllegalStateException(
                    String.format("Недостаточно средств. Доступно: %s, требуется: %s",
                            getAvailableBalance(), requiredAmount)
            );
        }

        // Проверяем, нет ли уже блокировки для этого аукциона
        if (frozenByAuction.containsKey(auctionId)) {
            throw new IllegalStateException(
                    String.format("Средства для аукциона %d уже заблокированы", auctionId)
            );
        }

        // Сохраняем информацию о блокировке
        FrozenDeposit frozen = new FrozenDeposit(auctionId, requiredAmount, FreezeReason.FIRST_BID);
        frozenByAuction.put(auctionId, frozen);

        this.blockedAmount = this.blockedAmount.add(requiredAmount);
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * Размораживает средства для указанного аукциона (по точной сумме)
     */
    public void unfreezeForAuction(Long auctionId, BigDecimal amount) {
        FrozenDeposit frozen = frozenByAuction.get(auctionId);

        if (frozen == null) {
            throw new IllegalStateException(
                    String.format("Нет заблокированных средств для аукциона %d", auctionId)
            );
        }

        if (frozen.getAmount().compareTo(amount) != 0) {
            throw new IllegalStateException(
                    String.format("Сумма разблокировки %s не соответствует заблокированной %s",
                            amount, frozen.getAmount())
            );
        }

        if (this.blockedAmount.compareTo(amount) < 0) {
            throw new IllegalStateException(
                    String.format("Недостаточно заблокированных средств. Заблокировано: %s, запрошено: %s",
                            this.blockedAmount, amount)
            );
        }

        this.blockedAmount = this.blockedAmount.subtract(amount);
        frozenByAuction.remove(auctionId);
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * Размораживает средства для указанного аукциона (по стартовой цене)
     */

    public void unfreezeForAuction(Long auctionId, BigDecimal startingPrice, boolean useStartingPrice) {
        BigDecimal requiredAmount = calculateRequiredDeposit(startingPrice);
        unfreezeForAuction(auctionId, requiredAmount);
    }

    /**
     * Отмечает пользователя как победителя аукциона
     * Депозит победителя остаётся замороженным до оплаты автомобиля
     */
    public void markAsWinner(Long auctionId) {
        FrozenDeposit frozen = frozenByAuction.get(auctionId);
        if (frozen != null) {
            frozen.markAsWinner();
        }
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * Пополнение депозита
     */
    public void deposit(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Сумма депозита должна быть больше 0");
        }
        this.balance = this.balance.add(amount);
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * Вывод средств с депозита
     */
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

    /**
     * Размораживает ВСЕ средства (использовать с осторожностью, только для админских операций)
     */
    public void unfreezeAll() {
        if (!frozenByAuction.isEmpty()) {
            log.warn("Unfreezing all frozen amounts. Affected auctions: {}", frozenByAuction.keySet());
            frozenByAuction.clear();
        }
        this.blockedAmount = BigDecimal.ZERO;
        this.updatedAt = OffsetDateTime.now();
    }

    // ==================== INNER CLASS ====================

    public enum FreezeReason {
        FIRST_BID,      // Заморозка при первой ставке
        ADMIN           // Заморозка админом
    }

    // Вспомогательный класс для хранения информации о замороженных средствах
    public static class FrozenDeposit {
        private final Long auctionId;
        private final BigDecimal amount;
        private final FreezeReason reason;
        private boolean winner;
        private final OffsetDateTime frozenAt;

        public FrozenDeposit(Long auctionId, BigDecimal amount, FreezeReason reason) {
            this.auctionId = auctionId;
            this.amount = amount;
            this.reason = reason;
            this.winner = false;
            this.frozenAt = OffsetDateTime.now();
        }

        public Long getAuctionId() { return auctionId; }
        public BigDecimal getAmount() { return amount; }
        public FreezeReason getReason() { return reason; }
        public boolean isWinner() { return winner; }
        public OffsetDateTime getFrozenAt() { return frozenAt; }

        public void markAsWinner() {
            this.winner = true;
        }

        @Override
        public String toString() {
            return String.format("FrozenDeposit{auctionId=%d, amount=%s, reason=%s, winner=%s}",
                    auctionId, amount, reason, winner);
        }
    }

    // Для логирования (добавьте, если используете lombok)
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(Deposit.class);

    @Override
    public String toString() {
        return String.format("Deposit{id=%d, userId=%d, balance=%s, blockedAmount=%s, available=%s, status=%s, frozenAuctions=%s}",
                id, userId, balance, blockedAmount, getAvailableBalance(), status, frozenByAuction.keySet());
    }
}