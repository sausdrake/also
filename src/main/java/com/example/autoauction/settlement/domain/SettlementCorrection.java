package com.example.autoauction.settlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Step 2C: an immutable, append-only correction fact layered on top of an existing
 * {@link Settlement} - never a mutation of the original row, never a status rollback. Zero or
 * more may exist per Settlement. See {@link SettlementEffectiveState} for how a correction
 * history is folded into the current effective business fact.
 *
 * <p>Exactly one of {@code correctedAmount}/{@code correctedSellerId} is populated, matching
 * {@link SettlementCorrectionType} - enforced here, the one place every construction path
 * converges through.
 */
public class SettlementCorrection {

    private Long id;
    private final Long settlementId;
    private final SettlementCorrectionType correctionType;
    private final BigDecimal correctedAmount;
    private final Long correctedSellerId;
    private final String reason;
    private final Long correctedByAdminId;
    private final OffsetDateTime correctedAt;
    private final String idempotencyKey;

    /** Creates a new, not-yet-persisted correction. */
    public SettlementCorrection(Long settlementId, SettlementCorrectionType correctionType,
                                 BigDecimal correctedAmount, Long correctedSellerId, String reason,
                                 Long correctedByAdminId, OffsetDateTime correctedAt, String idempotencyKey) {
        this.settlementId = Objects.requireNonNull(settlementId, "settlementId не может быть null");
        this.correctionType = Objects.requireNonNull(correctionType, "correctionType не может быть null");
        // Canonical money normalization (scale 2, HALF_UP) - same convention createPending already
        // applies to its own computed amounts. Normalized before validateValueCombination so the
        // positivity check runs against the same canonical value PostgreSQL's NUMERIC(19,2) will
        // actually persist. Null stays null - AMOUNT_CORRECTION is the only type that requires a
        // value; SELLER_CORRECTION/PAYOUT_INVALIDATED legitimately pass null here.
        BigDecimal normalizedCorrectedAmount = correctedAmount == null
                ? null : correctedAmount.setScale(2, RoundingMode.HALF_UP);
        validateValueCombination(correctionType, normalizedCorrectedAmount, correctedSellerId);
        this.correctedAmount = normalizedCorrectedAmount;
        this.correctedSellerId = correctedSellerId;
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason обязателен для корректировки Settlement");
        }
        this.reason = reason;
        this.correctedByAdminId = Objects.requireNonNull(correctedByAdminId, "correctedByAdminId не может быть null");
        this.correctedAt = Objects.requireNonNull(correctedAt, "correctedAt не может быть null");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey обязателен для корректировки Settlement");
        }
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * Reconstruction constructor, used exclusively by the persistence mapper. Re-validates the
     * type/value-combination invariant on every rebuild too, same discipline as every other
     * reconstruction constructor in this codebase.
     */
    public SettlementCorrection(Long id, Long settlementId, SettlementCorrectionType correctionType,
                                 BigDecimal correctedAmount, Long correctedSellerId, String reason,
                                 Long correctedByAdminId, OffsetDateTime correctedAt, String idempotencyKey) {
        this(settlementId, correctionType, correctedAmount, correctedSellerId, reason,
                correctedByAdminId, correctedAt, idempotencyKey);
        this.id = id;
    }

    /**
     * The one authoritative type&lt;-&gt;value-combination rule, mirroring
     * {@code SaleResolution.validateFaultPartyConsistency}'s established pattern in this
     * codebase - enforced once, here, never duplicated at any call site.
     */
    private static void validateValueCombination(SettlementCorrectionType type, BigDecimal correctedAmount,
                                                   Long correctedSellerId) {
        switch (type) {
            case AMOUNT_CORRECTION -> {
                if (correctedAmount == null) {
                    throw new IllegalArgumentException("correctedAmount обязателен для AMOUNT_CORRECTION");
                }
                if (correctedAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("correctedAmount должен быть больше 0: " + correctedAmount);
                }
                if (correctedSellerId != null) {
                    throw new IllegalArgumentException("correctedSellerId должен быть null для AMOUNT_CORRECTION");
                }
            }
            case SELLER_CORRECTION -> {
                if (correctedSellerId == null) {
                    throw new IllegalArgumentException("correctedSellerId обязателен для SELLER_CORRECTION");
                }
                if (correctedAmount != null) {
                    throw new IllegalArgumentException("correctedAmount должен быть null для SELLER_CORRECTION");
                }
            }
            case PAYOUT_INVALIDATED -> {
                if (correctedAmount != null || correctedSellerId != null) {
                    throw new IllegalArgumentException(
                            "PAYOUT_INVALIDATED не принимает correctedAmount или correctedSellerId");
                }
            }
        }
    }

    public Long getId() { return id; }
    public Long getSettlementId() { return settlementId; }
    public SettlementCorrectionType getCorrectionType() { return correctionType; }
    public BigDecimal getCorrectedAmount() { return correctedAmount; }
    public Long getCorrectedSellerId() { return correctedSellerId; }
    public String getReason() { return reason; }
    public Long getCorrectedByAdminId() { return correctedByAdminId; }
    public OffsetDateTime getCorrectedAt() { return correctedAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
}
