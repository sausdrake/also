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
     * X3-C: immutable snapshot of the SellerDiagnosticCommitment this auction was resolved
     * against at creation (via AuctionCommitmentResolver), NEVER re-derived from Vehicle's
     * current diagnosticRequestId pointer afterward - that pointer can move on to a later
     * diagnostic cycle while this auction's own backing commitment stays fixed forever. {@code
     * null} means a legacy, pre-X3-C auction - the application layer (AdminAuctionService)
     * fails a NEW auction closed before construction if no eligible commitment resolves, so
     * null is never assigned here by any current production path for a newly-created auction.
     */
    private Long sellerDiagnosticCommitmentId;

    /**
     * X3-C CORRECTIVE: guards {@link #setSellerDiagnosticCommitmentId}, see that method's
     * Javadoc. Not a persisted domain fact - purely an in-memory bookkeeping flag, so it is
     * transient like {@link #clock}/{@link #extensionProperties} above.
     */
    private transient boolean sellerDiagnosticCommitmentIdAssigned = false;

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

    /**
     * Step 2C: persisted discriminator between an ordinary winning bid and a Buy Now sale -
     * {@code null} for every historical auction finalized before this field existed (never
     * backfilled, never inferred from price). Written exactly once, by
     * {@link #setSaleFinalizationMethod(SaleFinalizationMethod)}, at the moment a winner is
     * first determined (either branch of finalization) - never touched again afterward.
     */
    private SaleFinalizationMethod saleFinalizationMethod;

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

    /**
     * Slice 1B: the deadline by which a {@code WAITING_FOR_PAYMENT} winner/seller must complete
     * payment/handover. {@code null} for every auction never yet transitioned to
     * {@code WAITING_FOR_PAYMENT}, and for historical auctions that reached it before this field
     * existed (never backfilled - see the locked migration boundary). Set exactly once atomically
     * with the transition (by {@code AuctionFinalizer}, via the plain setter below, immediately
     * alongside {@code setStatus(WAITING_FOR_PAYMENT)}) and thereafter only ever changed through
     * {@link #extendPaymentDeadline(OffsetDateTime)}. This is purely a technical/informational
     * fact - passing it never by itself changes {@link #status} or anything else (locked business
     * rule); only {@code completeSale()}/{@code markSaleFailed()} ever end the window this field
     * describes, and neither reads it.
     */
    private OffsetDateTime paymentDeadline;

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

    /**
     * Slice 1b-2 (B1 fix): deliberately scale-independent only. Before this slice,
     * reservePrice/buyNowPrice were always directly comparable to startingPrice (same
     * buyer-facing scale), so this constructor could safely enforce "reserve >= starting" /
     * "buyNow > starting" itself. That is no longer true: for a policy-bound auction,
     * reservePrice/buyNowPrice are SELLER-side amounts while startingPrice is buyer-facing, and
     * the constructor has no way to know at this point whether the auction being built will end
     * up policy-bound (policyVersionId is assigned afterward, via a setter, by
     * AdminAuctionService - see setPolicyVersionId's Javadoc). Comparing a seller-side value
     * against a buyer-facing one here would wrongly reject business-valid auctions (e.g.
     * sellerReserve=99,000 with startingPrice=100,000 and a 2% commission yields a buyer-facing
     * reserve of 100,980 - perfectly valid, but 99,000 < 100,000 would have thrown here).
     *
     * The ordering checks (reserve >= starting, buyNow > starting) are NOT removed - they still
     * apply to every auction, legacy and policy-bound alike - they simply moved to
     * AdminAuctionService.validatePolicyAwarePrices, the one place that actually knows which
     * scale to compare against (raw for legacy, buyer-facing for policy-bound). Only the
     * positivity checks below are truly scale-independent (a negative or zero price is invalid
     * regardless of which side of the commission split it represents) and so are the only ones
     * that legitimately belong in this constructor.
     */
    private void validatePrices(BigDecimal startingPrice, BigDecimal reservePrice, BigDecimal buyNowPrice) {
        if (startingPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Стартовая цена должна быть больше 0");
        }

        if (reservePrice != null) {
            if (reservePrice.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Резервная цена должна быть больше 0");
            }
        }

        if (buyNowPrice != null) {
            if (buyNowPrice.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Цена мгновенной покупки должна быть больше 0");
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
    /** VIN-AUTH-01: names stored before the fix contained "(VIN: …)" - it is never handed out. */
    public String getVehicleInfo() { return com.example.autoauction.vehicle.domain.VinText.withoutVin(vehicleInfo); }
    public BigDecimal getStartingPrice() { return startingPrice; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public BigDecimal getReservePrice() { return reservePrice; }
    public BigDecimal getBuyNowPrice() { return buyNowPrice; }
    public BigDecimal getMinBidStep() { return minBidStep; }

    /** AUCTION-01: the exact threshold a bid must reach - current price plus this auction's own step. */
    public BigDecimal minimumNextBid() { return currentPrice.add(minBidStep); }
    public Long getPolicyVersionId() { return policyVersionId; }
    public Long getSellerDiagnosticCommitmentId() { return sellerDiagnosticCommitmentId; }
    public BigDecimal getRequiredDepositAmount() { return requiredDepositAmount; }
    public OffsetDateTime getStartTime() { return startTime; }
    public OffsetDateTime getEndTime() { return endTime; }
    public AuctionStatus getStatus() { return status; }
    public Long getWinnerId() { return winnerId; }
    public BigDecimal getWinningBid() { return winningBid; }
    public Integer getTotalBids() { return totalBids; }
    public SaleFinalizationMethod getSaleFinalizationMethod() { return saleFinalizationMethod; }
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
    public OffsetDateTime getPaymentDeadline() { return paymentDeadline; }

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
     * X3-C CORRECTIVE: structurally enforces invariant I5 (immutable commitment snapshot).
     * This is NOT a constructor parameter - changing the constructor would ripple into every
     * one of the ~55 existing {@code new Auction(...)} call sites across the codebase (most in
     * modules with no reason to care about this field: sale, auth, common, integration, ...),
     * an unjustified mass ripple for fixing one field. It is also deliberately NOT a
     * reconstruction-only package-private method - {@link com.example.autoauction.admin.application.AdminAuctionService},
     * the one legitimate creation-time caller, lives in a different package and must still be
     * able to call it.
     *
     * Instead, this method may succeed AT MOST ONCE per Auction instance, guarded by
     * {@link #sellerDiagnosticCommitmentIdAssigned} - covering both of this class's legitimate
     * call sites (AdminAuctionService, once, immediately after AuctionCommitmentResolver
     * resolves the eligible commitment for a brand-new auction; AuctionMapper.toDomain(), once,
     * restoring the persisted value - including {@code null} for a legacy auction - when
     * reconstructing an existing auction onto a fresh instance). Any further call on the SAME
     * instance - regardless of whether the new value would differ from the old one - throws.
     * This replaces the previous plain setter, whose "single call site" protection was a
     * documented convention only (see the X3-C corrective fight that found this gap); the DB
     * UNIQUE constraint remains the backstop against the SAME commitment being claimed by two
     * DIFFERENT auctions, which this guard does not and cannot address on its own - the two
     * protections are complementary, not substitutes for each other.
     *
     * Unlike this field, {@link #policyVersionId}/{@link #requiredDepositAmount} remain plain,
     * convention-only setters - out of scope for this corrective fix, which targets only the
     * one invariant (I5) the X3-C fight actually found violated.
     */
    public void setSellerDiagnosticCommitmentId(Long sellerDiagnosticCommitmentId) {
        if (this.sellerDiagnosticCommitmentIdAssigned) {
            throw new IllegalStateException(
                    "sellerDiagnosticCommitmentId — неизменяемый снимок, уже назначен (" +
                    this.sellerDiagnosticCommitmentId + "). Переназначение, очистка или замена запрещены.");
        }
        this.sellerDiagnosticCommitmentId = sellerDiagnosticCommitmentId;
        this.sellerDiagnosticCommitmentIdAssigned = true;
        this.updatedAt = now();
    }

    /**
     * Step 2C: write-once guard keyed on the CURRENT VALUE being non-null, not on a separate
     * "has this setter ever been called" flag (unlike {@link #setSellerDiagnosticCommitmentId}).
     * This is a deliberate difference, not an oversight: that field is assigned exactly once, at
     * construction time, by a single call site with no reconstruction ever sharing the same
     * instance. {@code saleFinalizationMethod} is different - every Auction instance passes
     * through {@code AuctionMapper.toDomain()} first (restoring the persisted value, {@code null}
     * for an auction not yet finalized), and THEN, on the very same in-memory instance, through
     * {@code AuctionFinalizer.finalizeAuction()}/{@code finalizeWithBuyNow()} assigning the real
     * value for the first time. A boolean-flag guard would reject that legitimate second call. A
     * current-value guard handles both correctly: null-&gt;null (reconstructing a not-yet-finalized
     * auction) and null-&gt;real-value (finalizing it) both succeed; any call once the value is
     * already non-null - including an attempt to set it back to null - fails closed.
     *
     * @throws IllegalStateException if a non-null value is already assigned
     */
    public void setSaleFinalizationMethod(SaleFinalizationMethod saleFinalizationMethod) {
        if (this.saleFinalizationMethod != null) {
            throw new IllegalStateException(
                    "saleFinalizationMethod — неизменяемый снимок, уже назначен (" +
                    this.saleFinalizationMethod + "). Переназначение запрещено.");
        }
        this.saleFinalizationMethod = saleFinalizationMethod;
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

    /**
     * Slice 1B: plain setter, same convention as {@link #setConfirmationDeadline} - used for
     * reconstruction (AuctionMapper) and for the initial, system-computed assignment
     * (AuctionFinalizer, atomically alongside {@code setStatus(WAITING_FOR_PAYMENT)}). No status
     * guard here; {@link #extendPaymentDeadline(OffsetDateTime)} is the guarded entry point for
     * every subsequent (admin-driven) change.
     */
    public void setPaymentDeadline(OffsetDateTime paymentDeadline) { this.paymentDeadline = paymentDeadline; }

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
     * X2 (Authoritative Sale Completion): the authoritative successful-sale transition -
     * WAITING_FOR_PAYMENT -> SOLD. Deliberately not idempotent, same as start()/cancel() - a
     * second call against an already-SOLD (or any other status) auction fails closed with this
     * guard, by design (see AuctionFinalizer.completeSale()'s own Javadoc for why no idempotency
     * is needed on this side either). This method does not itself validate payment/handover
     * evidence - that precondition is enforced by the caller (AuctionFinalizer.completeSale())
     * via SaleResolutionService.createSuccess() before this method is ever invoked.
     */
    public void markSold() {
        if (this.status != AuctionStatus.WAITING_FOR_PAYMENT) {
            throw new IllegalStateException(
                    String.format("Невозможно завершить продажу аукциона в статусе '%s'. Только аукционы со статусом 'WAITING_FOR_PAYMENT' могут быть переведены в 'SOLD'",
                            this.status)
            );
        }
        this.status = AuctionStatus.SOLD;
        this.updatedAt = now();
    }

    /**
     * X3-A (SALE_FAILED outcomes): the authoritative failed-sale transition -
     * WAITING_FOR_PAYMENT -> SALE_FAILED. Deliberately not idempotent, same as markSold() - a
     * second call against an already-SALE_FAILED (or any other status) auction fails closed
     * with this guard. This method does not itself know or validate which outcome (B/
     * BUYER_FAULT/PLATFORM_FAULT/C) is being recorded - that classification lives entirely on
     * the SaleResolution created by the caller (SaleFailureResolutionOrchestrator) before this
     * method is invoked.
     */
    public void markSaleFailed() {
        if (this.status != AuctionStatus.WAITING_FOR_PAYMENT) {
            throw new IllegalStateException(
                    String.format("Невозможно зафиксировать неудачную продажу аукциона в статусе '%s'. Только аукционы со статусом 'WAITING_FOR_PAYMENT' могут быть переведены в 'SALE_FAILED'",
                            this.status)
            );
        }
        this.status = AuctionStatus.SALE_FAILED;
        this.updatedAt = now();
    }

    /**
     * Slice 1B: admin-driven payment-deadline override/extension - the only guarded entry point
     * for changing {@link #paymentDeadline} after its initial, system-computed assignment.
     * Permitted only while the auction is {@code WAITING_FOR_PAYMENT} (locked rule): this guard
     * exists solely to prevent mutating a deadline that no longer has operational meaning once
     * the auction has left this window - it deliberately does NOT gate
     * {@code completeSale()}/{@code markSaleFailed()} the other way (those never read this
     * field).  No "new deadline must be in the future/later than the previous one" rule is
     * enforced - the locked business rule explicitly allows an admin to set a past deadline,
     * which simply makes the auction immediately overdue per {@code now > paymentDeadline}.
     *
     * <p>Requires an existing (non-null) deadline: a historical auction that reached
     * {@code WAITING_FOR_PAYMENT} before this feature existed has no initial deadline and is
     * deliberately never retroactively given one by this method - "do not invent deadlines for
     * historical auctions" per the locked migration boundary. Such an auction simply cannot use
     * this override path.
     */
    public void extendPaymentDeadline(OffsetDateTime newDeadline) {
        Objects.requireNonNull(newDeadline, "Новый платёжный дедлайн не может быть null");
        if (this.status != AuctionStatus.WAITING_FOR_PAYMENT) {
            throw new IllegalStateException(
                    String.format("Невозможно изменить платёжный дедлайн аукциона %d в статусе '%s'. Изменение дедлайна доступно только в статусе 'WAITING_FOR_PAYMENT'",
                            this.id, this.status));
        }
        if (this.paymentDeadline == null) {
            throw new IllegalStateException(
                    String.format("У аукциона %d отсутствует исходный платёжный дедлайн (вероятно, аукцион создан до введения этой функции) - изменение через этот метод недоступно",
                            this.id));
        }
        this.paymentDeadline = newDeadline;
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

        // AUCTION-01: a bid is money - som and tyiyn - that fits its column: never a finer amount the database
        // would round silently, never one that would overflow NUMERIC(10,2) into a database error
        if (!BidStepPolicy.isStorableMoney(amount)) {
            throw new IllegalArgumentException(
                    "Сумма ставки: больше 0, не больше 99999999.99 и не больше двух знаков после запятой");
        }
        BigDecimal minimumNextBid = minimumNextBid();
        if (amount.compareTo(minimumNextBid) < 0) {
            throw new IllegalArgumentException(
                    String.format("Сумма ставки %s слишком мала. Минимальная следующая ставка: %s",
                            amount.toPlainString(), BidStepPolicy.smallestValidBid(minimumNextBid).toPlainString())
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
