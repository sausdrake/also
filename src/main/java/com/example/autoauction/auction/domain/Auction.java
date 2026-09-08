// auction/domain/Auction.java
package com.example.autoauction.auction.domain;

import com.example.autoauction.config.AuctionExtensionProperties;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;

@Slf4j
public class Auction {
    private Long id;
    private Long vehicleId;
    private String vehicleInfo;

    private BigDecimal startingPrice;
    private BigDecimal currentPrice;
    private BigDecimal reservePrice;
    private BigDecimal buyNowPrice;
    private BigDecimal minBidStep;

    /**
     * Slice 1b-2: reference to the immutable PolicyVersion this auction was created under.
     * {@code null} means a legacy, pre-feature auction, grandfathered under the old semantics
     * where {@link #reservePrice}/{@link #buyNowPrice} are themselves already buyer-facing.
     * Non-null means a policy-bound auction, where {@link #reservePrice}/{@link #buyNowPrice}
     * store the SELLER-side amount, and the buyer-facing value must be derived via
     * {@link #resolveBuyerFacingReservePrice(BigDecimal)}/{@link #resolveBuyerFacingBuyNowPrice(BigDecimal)}.
     * commissionRate itself is deliberately NOT stored here (no denormalized copy) - every
     * consumer resolves it fresh via the immutable PolicyVersion referenced by this id, which
     * can never change once published (see the Slice 1b-2 architecture lock for why
     * denormalization was rejected).
     */
    private Long policyVersionId;

    /**
     * Фиксированная сумма депозита, требуемая для участия в ЭТОМ аукционе, задаётся
     * администратором индивидуально для каждого лота (НЕ процент от startingPrice).
     * Nullable для обратной совместимости с уже существующими записями аукционов,
     * созданными до введения этого поля — для таких записей ставки/Buy Now блокируются
     * явной ошибкой (см. BidService), а не тихим fallback-расчётом.
     */
    private BigDecimal requiredDepositAmount;

    private OffsetDateTime startTime;
    private OffsetDateTime endTime;

    private AuctionStatus status;
    private Long winnerId;
    private BigDecimal winningBid;
    private Integer totalBids;

    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    private Long version;

    private int extensionCount = 0;

    private String mainPhotoUrl;
    private String additionalPhotoUrls; // JSON-строка ["url1","url2"]
    private String videoUrl;
    private boolean confirmedStart = false;
    private OffsetDateTime confirmationDeadline;

    private transient Clock clock;
    private transient AuctionExtensionProperties extensionProperties;

    // Конструктор для создания нового аукциона
    public Auction(Long vehicleId, BigDecimal startingPrice, BigDecimal reservePrice,
                   BigDecimal buyNowPrice, BigDecimal minBidStep,
                   OffsetDateTime startTime, OffsetDateTime endTime, Long createdBy) {
        this(vehicleId, startingPrice, reservePrice, buyNowPrice, minBidStep,
                startTime, endTime, createdBy, Clock.systemUTC());
    }

    // Конструктор с поддержкой Clock (для тестов)
    public Auction(Long vehicleId, BigDecimal startingPrice, BigDecimal reservePrice,
                   BigDecimal buyNowPrice, BigDecimal minBidStep,
                   OffsetDateTime startTime, OffsetDateTime endTime, Long createdBy, Clock clock) {
        Objects.requireNonNull(vehicleId, "ID автомобиля не может быть null");
        Objects.requireNonNull(startingPrice, "Стартовая цена не может быть null");
        Objects.requireNonNull(minBidStep, "Шаг ставки не может быть null");
        Objects.requireNonNull(startTime, "Время начала не может быть null");
        Objects.requireNonNull(endTime, "Время окончания не может быть null");
        Objects.requireNonNull(createdBy, "ID создателя не может быть null");

        this.clock = clock != null ? clock : Clock.systemUTC();

        validatePrices(startingPrice, reservePrice, buyNowPrice);
        validateTime(startTime, endTime);

        this.vehicleId = vehicleId;
        this.startingPrice = startingPrice;
        this.currentPrice = startingPrice;
        this.reservePrice = reservePrice;
        this.buyNowPrice = buyNowPrice;
        this.minBidStep = minBidStep;
        this.startTime = startTime;
        this.endTime = endTime;
        this.createdBy = createdBy;
        this.status = AuctionStatus.CREATED;
        this.totalBids = 0;
        this.createdAt = now();
        this.updatedAt = now();
        this.version = 0L;
        this.extensionCount = 0;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    public void setExtensionProperties(AuctionExtensionProperties extensionProperties) {
        this.extensionProperties = extensionProperties;
    }

    private AuctionExtensionProperties getExtensionProperties() {
        if (extensionProperties == null) {
            // Если конфигурация не внедрена, используем значения из пропертей
            // Создаём с дефолтными значениями (безопасно, но лучше внедрять)
            log.warn("AuctionExtensionProperties not injected, using defaults for auction {}", this.id);
            AuctionExtensionProperties props = new AuctionExtensionProperties();
            props.setEnabled(true);
            props.setThresholdSeconds(30);
            props.setExtensionMinutes(5);
            props.setMaxExtensions(0);
            return props;
        }
        return extensionProperties;
    }

    private void validatePrices(BigDecimal startingPrice, BigDecimal reservePrice, BigDecimal buyNowPrice) {
        if (startingPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Стартовая цена должна быть больше 0");
        }

        if (reservePrice != null) {
            if (reservePrice.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Резервная цена должна быть больше 0");
            }
            if (reservePrice.compareTo(startingPrice) < 0) {
                throw new IllegalArgumentException(
                        String.format("Резервная цена (%s) не может быть меньше стартовой (%s)",
                                reservePrice, startingPrice)
                );
            }
        }

        if (buyNowPrice != null) {
            if (buyNowPrice.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Цена мгновенной покупки должна быть больше 0");
            }
            if (buyNowPrice.compareTo(startingPrice) <= 0) {
                throw new IllegalArgumentException(
                        String.format("Цена мгновенной покупки (%s) должна быть больше стартовой (%s)",
                                buyNowPrice, startingPrice)
                );
            }
        }
    }

    private void validateTime(OffsetDateTime startTime, OffsetDateTime endTime) {
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException(
                    String.format("Время окончания (%s) должно быть позже времени начала (%s)",
                            endTime, startTime)
            );
        }
    }

    // ==================== ГЕТТЕРЫ ====================

    public Long getId() { return id; }
    public Long getVehicleId() { return vehicleId; }
    public String getVehicleInfo() { return vehicleInfo; }
    public BigDecimal getStartingPrice() { return startingPrice; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public BigDecimal getReservePrice() { return reservePrice; }
    public BigDecimal getBuyNowPrice() { return buyNowPrice; }
    public BigDecimal getMinBidStep() { return minBidStep; }
    public Long getPolicyVersionId() { return policyVersionId; }
    public BigDecimal getRequiredDepositAmount() { return requiredDepositAmount; }
    public OffsetDateTime getStartTime() { return startTime; }
    public OffsetDateTime getEndTime() { return endTime; }
    public AuctionStatus getStatus() { return status; }
    public Long getWinnerId() { return winnerId; }
    public BigDecimal getWinningBid() { return winningBid; }
    public Integer getTotalBids() { return totalBids; }
    public Long getCreatedBy() { return createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
    public int getExtensionCount() { return extensionCount; }
    public String getMainPhotoUrl() { return mainPhotoUrl; }
    public String getAdditionalPhotoUrls() { return additionalPhotoUrls; }
    public String getVideoUrl() { return videoUrl; }
    public boolean isConfirmedStart() { return confirmedStart; }
    public OffsetDateTime getConfirmationDeadline() { return confirmationDeadline; }

    // ==================== СЕТТЕРЫ ====================

    public void setId(Long id) {
        Objects.requireNonNull(id, "ID не может быть null");
        this.id = id;
    }

    public void setCurrentPrice(BigDecimal currentPrice) {
        Objects.requireNonNull(currentPrice, "Текущая цена не может быть null");
        if (currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Текущая цена должна быть больше 0");
        }
        this.currentPrice = currentPrice;
        this.updatedAt = now();
    }

    public void setStatus(AuctionStatus status) {
        Objects.requireNonNull(status, "Статус не может быть null");
        this.status = status;
        this.updatedAt = now();
    }

    public void setWinnerId(Long winnerId) {
        this.winnerId = winnerId;
        this.updatedAt = now();
    }

    public void setWinningBid(BigDecimal winningBid) {
        if (winningBid != null && winningBid.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Выигрышная ставка должна быть больше 0");
        }
        this.winningBid = winningBid;
        this.updatedAt = now();
    }

    public void setTotalBids(Integer totalBids) {
        Objects.requireNonNull(totalBids, "Количество ставок не может быть null");
        if (totalBids < 0) {
            throw new IllegalArgumentException("Количество ставок не может быть отрицательным");
        }
        this.totalBids = totalBids;
        this.updatedAt = now();
    }

    public void setVehicleInfo(String vehicleInfo) {
        this.vehicleInfo = vehicleInfo;
        this.updatedAt = now();
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        Objects.requireNonNull(createdAt, "Дата создания не может быть null");
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        Objects.requireNonNull(updatedAt, "Дата обновления не может быть null");
        this.updatedAt = updatedAt;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Плоский сеттер значения без бизнес-guard'а — как и другие сеттеры этого класса
     * (setStatus, setTotalBids), используется как для реконструкции из БД (AuctionMapper),
     * так и для установки при создании (AdminAuctionService). В этом кодбейзе нет
     * endpoint'а редактирования аукциона, поэтому единственный вызывающий бизнес-код —
     * создание нового аукциона (totalBids=0, участников нет). Если в будущем появится
     * возможность редактировать существующий аукцион, вызывающий код ОБЯЗАН сам проверить
     * !auction.isFinished() && (auction.getTotalBids() == null || auction.getTotalBids() == 0)
     * перед вызовом этого метода на уже персистентном аукционе — иначе можно изменить
     * требуемый депозит уже после того, как участники заморозили деньги под старую сумму.
     */
    public void setRequiredDepositAmount(BigDecimal requiredDepositAmount) {
        if (requiredDepositAmount != null) {
            if (requiredDepositAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Требуемый депозит должен быть больше 0");
            }
            requiredDepositAmount = requiredDepositAmount.setScale(2, RoundingMode.HALF_UP);
        }
        this.requiredDepositAmount = requiredDepositAmount;
        this.updatedAt = now();
    }

    /**
     * Slice 1b-2: plain setter, matching this class's existing single-call-site discipline
     * (see {@link #setRequiredDepositAmount}'s own Javadoc for the identical pattern) rather
     * than a stricter no-setter design - the only legitimate call sites in the entire codebase
     * are (1) AdminAuctionService, exactly once, immediately after constructing a brand-new
     * auction and resolving its PolicyVersion, and (2) AuctionMapper.toDomain(), exactly once,
     * restoring the persisted value when reconstructing an existing auction. Business rule:
     * once assigned, this value must NEVER be reassigned - no business operation in this
     * codebase calls this method a second time on the same auction, but that is a documented
     * discipline, not a structurally-enforced one, consistent with the level of protection
     * every other similarly-critical field on this class (winnerId, winningBid,
     * requiredDepositAmount) already has.
     */
    public void setPolicyVersionId(Long policyVersionId) {
        this.policyVersionId = policyVersionId;
        this.updatedAt = now();
    }

    /**
     * Slice 1b-2: buyer-facing reserve. For a legacy auction ({@link #policyVersionId} null),
     * {@link #reservePrice} is already buyer-facing and is returned unchanged - commissionRate
     * is never dereferenced in this branch. For a policy-bound auction, {@link #reservePrice}
     * is the seller amount, and commissionRate (resolved by the caller from this auction's own
     * immutable PolicyVersion - never a live/global setting) is required, not optional.
     */
    public BigDecimal resolveBuyerFacingReservePrice(BigDecimal commissionRate) {
        if (reservePrice == null) return null;
        if (policyVersionId == null) return reservePrice;
        Objects.requireNonNull(commissionRate, "commissionRate обязателен для policy-bound аукциона");
        return applyCommissionMarkup(reservePrice, commissionRate);
    }

    /**
     * Slice 1b-2: buyer-facing Buy Now amount. Same legacy/policy-bound split as
     * {@link #resolveBuyerFacingReservePrice(BigDecimal)}.
     */
    public BigDecimal resolveBuyerFacingBuyNowPrice(BigDecimal commissionRate) {
        if (buyNowPrice == null) return null;
        if (policyVersionId == null) return buyNowPrice;
        Objects.requireNonNull(commissionRate, "commissionRate обязателен для policy-bound аукциона");
        return applyCommissionMarkup(buyNowPrice, commissionRate);
    }

    /**
     * Single authoritative markup calculation, used by both resolver methods above - the only
     * place `sellerAmount * (1 + commissionRate)` is ever computed in this codebase. scale=2,
     * HALF_UP, per the locked monetary rounding rule. No floating-point arithmetic.
     */
    private static BigDecimal applyCommissionMarkup(BigDecimal sellerAmount, BigDecimal commissionRate) {
        return sellerAmount.multiply(BigDecimal.ONE.add(commissionRate))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public void setExtensionCount(int extensionCount) {
        this.extensionCount = extensionCount;
    }

    public void setMainPhotoUrl(String mainPhotoUrl) { this.mainPhotoUrl = mainPhotoUrl; }
    public void setAdditionalPhotoUrls(String additionalPhotoUrls) { this.additionalPhotoUrls = additionalPhotoUrls; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }
    public void setConfirmedStart(boolean confirmedStart) { this.confirmedStart = confirmedStart; }
    public void setConfirmationDeadline(OffsetDateTime confirmationDeadline) { this.confirmationDeadline = confirmationDeadline; }

    // ==================== БИЗНЕС-МЕТОДЫ ====================

    public void updateVehicleInfo(String vehicleInfo) {
        this.vehicleInfo = vehicleInfo;
        this.updatedAt = now();
    }

    public void confirmStart(int confirmHours) {
        if (this.status != AuctionStatus.CREATED) {
            throw new IllegalStateException(
                    String.format("Нельзя подтвердить запуск аукциона в статусе '%s'", this.status));
        }
        this.confirmedStart = true;
        this.confirmationDeadline = this.startTime.minusHours(confirmHours);
        this.updatedAt = now();
    }

    public void start() {
        if (this.status != AuctionStatus.CREATED) {
            throw new IllegalStateException(
                    String.format("Невозможно запустить аукцион в статусе '%s'. Только аукционы со статусом 'CREATED' могут быть запущены",
                            this.status)
            );
        }
        this.status = AuctionStatus.ACTIVE;
        this.updatedAt = now();
    }

    /**
     * Slice 1a: single source of truth for "can this auction still be cancelled" -
     * WAITING_FOR_PAYMENT and every terminal status (SOLD/SALE_FAILED/EXPIRED/CANCELLED/
     * legacy COMPLETED) are deliberately excluded. Used by {@link #cancel()} itself and by
     * every caller that needs to reject a cancellation attempt before invoking it, so the
     * invariant lives in exactly one place rather than being re-derived from isFinished()
     * (which WAITING_FOR_PAYMENT deliberately does NOT satisfy).
     */
    public boolean isCancellable() {
        return this.status == AuctionStatus.CREATED || this.status == AuctionStatus.ACTIVE;
    }

    public void cancel() {
        if (!isCancellable()) {
            throw new IllegalStateException(
                    String.format("Невозможно отменить аукцион в статусе '%s'. Только аукционы со статусами 'CREATED' или 'ACTIVE' могут быть отменены",
                            this.status)
            );
        }
        this.status = AuctionStatus.CANCELLED;
        this.updatedAt = now();
    }

    private boolean shouldExtendAuction(OffsetDateTime bidTime) {
        AuctionExtensionProperties props = getExtensionProperties();
        if (!props.isEnabled()) {
            return false;
        }

        if (props.getMaxExtensions() > 0 && extensionCount >= props.getMaxExtensions()) {
            log.debug("Auction {} reached max extensions limit ({})", this.id, props.getMaxExtensions());
            return false;
        }

        long secondsUntilEnd = java.time.Duration.between(bidTime, this.endTime).getSeconds();
        boolean shouldExtend = secondsUntilEnd <= props.getThresholdSeconds() && secondsUntilEnd > 0;

        if (shouldExtend) {
            log.debug("Auction {} should extend: {} seconds until end, threshold={}",
                    this.id, secondsUntilEnd, props.getThresholdSeconds());
        }

        return shouldExtend;
    }

    private void extendAuction() {
        AuctionExtensionProperties props = getExtensionProperties();
        this.endTime = this.endTime.plusMinutes(props.getExtensionMinutes());
        this.extensionCount++;
        this.updatedAt = now();

        log.info("Auction {} extended #{} by {} minutes. New end time: {}",
                this.id, this.extensionCount, props.getExtensionMinutes(), this.endTime);
    }

    /**
     * Обычная ставка на аукционе. Buy Now не обрабатывается здесь -
     * это отдельный эндпоинт.
     */
    public void addBid(BigDecimal amount, Long bidderId, OffsetDateTime bidTime) {
        Objects.requireNonNull(amount, "Сумма ставки не может быть null");
        Objects.requireNonNull(bidderId, "ID участника не может быть null");
        Objects.requireNonNull(bidTime, "Время ставки не может быть null");

        if (this.status != AuctionStatus.ACTIVE) {
            throw new IllegalStateException(
                    String.format("Невозможно сделать ставку на аукцион в статусе '%s'. Только активные аукционы принимают ставки",
                            this.status)
            );
        }

        if (this.endTime.isBefore(bidTime)) {
            throw new IllegalStateException(
                    String.format("Время аукциона истекло. Время окончания: %s", this.endTime)
            );
        }

        BigDecimal minimumNextBid = this.currentPrice.add(this.minBidStep);
        if (amount.compareTo(minimumNextBid) < 0) {
            throw new IllegalArgumentException(
                    String.format("Сумма ставки %s слишком мала. Минимальная следующая ставка: %s",
                            amount, minimumNextBid)
            );
        }

        if (bidderId.equals(this.createdBy)) {
            throw new IllegalArgumentException("Нельзя делать ставки на собственный аукцион");
        }

        // Проверяем, нужно ли продлить аукцион
        boolean extended = shouldExtendAuction(bidTime);
        if (extended) {
            extendAuction();
        }

        // Обычная ставка
        this.currentPrice = amount;
        this.winnerId = bidderId;
        this.winningBid = amount;
        this.totalBids++;
        this.updatedAt = now();

        if (extended) {
            log.debug("Auction {} extended due to late bid. New end time: {}", this.id, this.endTime);
        }
    }

    public void addBid(BigDecimal amount, Long bidderId) {
        addBid(amount, bidderId, now());
    }

    public boolean isActive() {
        return this.status == AuctionStatus.ACTIVE;
    }

    @SuppressWarnings("deprecation") // legacy COMPLETED rows are still finished, per Slice 1a contract
    public boolean isFinished() {
        return this.status == AuctionStatus.COMPLETED ||
                this.status == AuctionStatus.SOLD ||
                this.status == AuctionStatus.SALE_FAILED ||
                this.status == AuctionStatus.EXPIRED ||
                this.status == AuctionStatus.CANCELLED;
    }

    public boolean canBeStarted() {
        return this.status == AuctionStatus.CREATED &&
                this.startTime.isAfter(now());
    }

    @Override
    public String toString() {
        return String.format("Auction{id=%d, vehicleId=%d, status=%s, currentPrice=%s, version=%d, extensions=%d}",
                id, vehicleId, status, currentPrice, version, extensionCount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Auction auction = (Auction) o;
        return Objects.equals(id, auction.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
