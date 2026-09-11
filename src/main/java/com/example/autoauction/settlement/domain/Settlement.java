package com.example.autoauction.settlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Step 2C: the evidence-oriented record of a successful sale's seller-payout consequence.
 *
 * <p><b>Not a seller wallet.</b> There is no software transfer of money to the seller and no
 * platform wallet anywhere in this aggregate. The system cannot verify physical cash movement -
 * {@link #recordPayout} durably records only that an admin <i>attested</i> the payout occurred,
 * never a software-verified guarantee.
 *
 * <p><b>Immutable economics.</b> {@link #expectedBuyerPaidAmount}, {@link #expectedSellerAmount},
 * and {@link #expectedCommission} are fixed forever at construction and represent the economics
 * of the completed sale - they are never correction targets (see {@link SettlementCorrection};
 * {@code AMOUNT_CORRECTION} corrects only the effective <i>attested</i> payout, never these
 * fields). Conservation is enforced here, once, by construction:
 * {@code expectedSellerAmount + expectedCommission == expectedBuyerPaidAmount} - commission is
 * always the subtraction remainder the caller already computed, never independently
 * recalculated by this constructor.
 *
 * <p><b>Lifecycle:</b> {@code PENDING -> PAYOUT_RECORDED}, one-way, no {@code FAILED} state (see
 * {@link SettlementStatus}). Once {@link #recordPayout} succeeds, {@link #attestedPayoutAmount},
 * {@link #recordedByAdminId}, and {@link #recordedAt} are fixed forever.
 *
 * <p><b>Expected vs. attested.</b> {@link #expectedSellerAmount} is this sale's computed
 * economic fact. {@link #attestedPayoutAmount} is what an admin states was physically handed to
 * the seller - the two are allowed to diverge (a real-world handover error), and a divergence is
 * preserved as plain visible evidence on this same row, never rejected, never silently
 * normalized, and never used to recompute {@link #expectedCommission}.
 */
public class Settlement {

    private Long id;
    private final Long auctionId;
    private final Long sellerId;
    private final BigDecimal expectedBuyerPaidAmount;
    private final BigDecimal expectedSellerAmount;
    private final BigDecimal expectedCommission;
    private SettlementStatus status;
    private BigDecimal attestedPayoutAmount;
    private Long recordedByAdminId;
    private OffsetDateTime recordedAt;
    private final OffsetDateTime createdAt;

    /**
     * Creates a new, not-yet-persisted Settlement in {@link SettlementStatus#PENDING}. The
     * caller (application layer) is responsible for having already deterministically resolved
     * {@code sellerId} and computed the three expected economic values using the locked
     * formulas for this sale's {@code SaleFinalizationMethod} - this constructor's only job is
     * to validate the result, never to perform the economic calculation itself.
     */
    public Settlement(Long auctionId, Long sellerId, BigDecimal expectedBuyerPaidAmount,
                       BigDecimal expectedSellerAmount, BigDecimal expectedCommission,
                       OffsetDateTime createdAt) {
        this.auctionId = Objects.requireNonNull(auctionId, "auctionId не может быть null");
        this.sellerId = Objects.requireNonNull(sellerId, "sellerId не может быть null");
        this.expectedBuyerPaidAmount = requirePositive(expectedBuyerPaidAmount, "expectedBuyerPaidAmount");
        this.expectedSellerAmount = requirePositive(expectedSellerAmount, "expectedSellerAmount");
        Objects.requireNonNull(expectedCommission, "expectedCommission не может быть null");
        if (expectedCommission.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(
                    "expectedCommission не может быть отрицательной: " + expectedCommission);
        }
        if (this.expectedSellerAmount.add(expectedCommission).compareTo(this.expectedBuyerPaidAmount) != 0) {
            throw new IllegalArgumentException(String.format(
                    "Нарушен инвариант сохранения: expectedSellerAmount (%s) + expectedCommission (%s) != "
                            + "expectedBuyerPaidAmount (%s)",
                    this.expectedSellerAmount, expectedCommission, this.expectedBuyerPaidAmount));
        }
        this.expectedCommission = expectedCommission;
        this.status = SettlementStatus.PENDING;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt не может быть null");
    }

    /**
     * Reconstruction constructor, used exclusively by the persistence mapper. Re-validates the
     * conservation invariant on every rebuild too - a row that somehow violated it (corrupted
     * data) must never be silently accepted as a valid in-memory Settlement.
     */
    public Settlement(Long id, Long auctionId, Long sellerId, BigDecimal expectedBuyerPaidAmount,
                       BigDecimal expectedSellerAmount, BigDecimal expectedCommission,
                       SettlementStatus status, BigDecimal attestedPayoutAmount,
                       Long recordedByAdminId, OffsetDateTime recordedAt, OffsetDateTime createdAt) {
        this(auctionId, sellerId, expectedBuyerPaidAmount, expectedSellerAmount, expectedCommission, createdAt);
        this.id = id;
        this.status = Objects.requireNonNull(status, "status не может быть null");
        this.attestedPayoutAmount = attestedPayoutAmount;
        this.recordedByAdminId = recordedByAdminId;
        this.recordedAt = recordedAt;
    }

    /**
     * {@code PENDING -> PAYOUT_RECORDED}. Records that {@code adminId} attests the seller was
     * physically handed {@code attestedAmount} - a durable attestation, never a
     * software-verified guarantee of physical cash movement. Does not compare
     * {@code attestedAmount} against {@link #expectedSellerAmount} - a divergence is legal and
     * preserved, not validated away.
     *
     * @throws IllegalStateException if the current status is not PENDING
     */
    public void recordPayout(BigDecimal attestedAmount, Long adminId, OffsetDateTime recordedAt) {
        if (this.status != SettlementStatus.PENDING) {
            throw new IllegalStateException(
                    "Выплата по Settlement " + id + " уже зафиксирована, текущий статус: " + this.status);
        }
        // Canonical money normalization (scale 2, HALF_UP) - same convention createPending already
        // applies to its own computed amounts. Normalized before requirePositive so the value
        // actually validated/stored is the one PostgreSQL's NUMERIC(19,2) will persist, not a
        // higher-scale input that would otherwise diverge from the persisted row.
        BigDecimal normalizedAttestedAmount = attestedAmount == null
                ? null : attestedAmount.setScale(2, RoundingMode.HALF_UP);
        this.attestedPayoutAmount = requirePositive(normalizedAttestedAmount, "attestedAmount");
        this.recordedByAdminId = Objects.requireNonNull(adminId, "adminId не может быть null");
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt не может быть null");
        this.status = SettlementStatus.PAYOUT_RECORDED;
    }

    public Long getId() { return id; }
    public Long getAuctionId() { return auctionId; }
    public Long getSellerId() { return sellerId; }
    public BigDecimal getExpectedBuyerPaidAmount() { return expectedBuyerPaidAmount; }
    public BigDecimal getExpectedSellerAmount() { return expectedSellerAmount; }
    public BigDecimal getExpectedCommission() { return expectedCommission; }
    public SettlementStatus getStatus() { return status; }
    public BigDecimal getAttestedPayoutAmount() { return attestedPayoutAmount; }
    public Long getRecordedByAdminId() { return recordedByAdminId; }
    public OffsetDateTime getRecordedAt() { return recordedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    private static BigDecimal requirePositive(BigDecimal value, String field) {
        Objects.requireNonNull(value, field + " не может быть null");
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(field + " должен быть больше 0: " + value);
        }
        return value;
    }
}
