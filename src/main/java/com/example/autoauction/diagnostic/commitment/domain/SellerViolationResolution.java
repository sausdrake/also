package com.example.autoauction.diagnostic.commitment.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * X3-E: the immutable, append-only audit header for a commitment's seller-violation financial
 * resolution (Outcome C) - "commitment {@link #sellerDiagnosticCommitmentId} was resolved as a
 * seller violation, via {@link #triggerSource}, under policy {@link #policyVersionId}, with
 * aggregate penalty {@link #appliedPenaltyAmount}."
 *
 * At most one of these can ever exist per commitment (DB UNIQUE on
 * sellerDiagnosticCommitmentId, V40) - provably safe, not merely convenient: a row is only
 * ever created in the SAME transaction as a WON claim on
 * SellerDiagnosticCommitment's own AWAITING_FINALIZATION -&gt; FINALIZED transition, which can
 * only ever succeed once for a given commitment (FINALIZED is terminal) - see the X3-E
 * pre-implementation consistency check for the full proof, including why this holds even
 * though {@link TriggerSource#DIAGNOSTIC_REJECTION} and {@link TriggerSource#SALE_FAILED_C}
 * can, in one corner case (a rejected-then-reworked-then-approved DiagnosticRequest), both
 * legitimately attempt resolution against the same commitment.
 *
 * rawPenaltyAmount is the uncapped sum of every {@link SellerViolationResolutionItem}'s own
 * calculatedPenaltyAmount; appliedPenaltyAmount is capped at the commitment's original frozen
 * amount; refundAmount = frozenCommitment - appliedPenaltyAmount. The cap is never allocated
 * back down to individual items (locked X3-E §6) - only this header reflects it.
 *
 * Deliberately has no setters - immutable after creation, same shape as HandoverEvidence/
 * PaymentEvidence/SaleResolution's own immutable fields.
 */
public class SellerViolationResolution {

    private Long id;
    private final Long sellerDiagnosticCommitmentId;
    private final TriggerSource triggerSource;
    private final Long auctionId;
    private final Long policyVersionId;
    private final BigDecimal rawPenaltyAmount;
    private final BigDecimal appliedPenaltyAmount;
    private final BigDecimal refundAmount;
    private final Long resolvedBy;
    private final OffsetDateTime resolvedAt;
    private final OffsetDateTime createdAt;

    public SellerViolationResolution(Long sellerDiagnosticCommitmentId, TriggerSource triggerSource, Long auctionId,
                                      Long policyVersionId, BigDecimal rawPenaltyAmount, BigDecimal appliedPenaltyAmount,
                                      BigDecimal refundAmount, Long resolvedBy, OffsetDateTime resolvedAt,
                                      OffsetDateTime createdAt) {
        Objects.requireNonNull(sellerDiagnosticCommitmentId, "sellerDiagnosticCommitmentId не может быть null");
        Objects.requireNonNull(triggerSource, "triggerSource не может быть null");
        Objects.requireNonNull(policyVersionId, "policyVersionId не может быть null");
        Objects.requireNonNull(rawPenaltyAmount, "rawPenaltyAmount не может быть null");
        Objects.requireNonNull(appliedPenaltyAmount, "appliedPenaltyAmount не может быть null");
        Objects.requireNonNull(refundAmount, "refundAmount не может быть null");
        Objects.requireNonNull(resolvedBy, "resolvedBy не может быть null");
        Objects.requireNonNull(resolvedAt, "resolvedAt не может быть null");
        Objects.requireNonNull(createdAt, "createdAt не может быть null");
        validateTriggerAuctionConsistency(triggerSource, auctionId);
        if (appliedPenaltyAmount.compareTo(rawPenaltyAmount) > 0) {
            throw new IllegalArgumentException(
                    "appliedPenaltyAmount (" + appliedPenaltyAmount + ") не может превышать rawPenaltyAmount (" + rawPenaltyAmount + ")");
        }
        if (refundAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("refundAmount не может быть отрицательным: " + refundAmount);
        }
        this.sellerDiagnosticCommitmentId = sellerDiagnosticCommitmentId;
        this.triggerSource = triggerSource;
        this.auctionId = auctionId;
        this.policyVersionId = policyVersionId;
        this.rawPenaltyAmount = rawPenaltyAmount;
        this.appliedPenaltyAmount = appliedPenaltyAmount;
        this.refundAmount = refundAmount;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = resolvedAt;
        this.createdAt = createdAt;
    }

    /**
     * Reconstruction constructor, used exclusively by the persistence mapper.
     */
    public SellerViolationResolution(Long id, Long sellerDiagnosticCommitmentId, TriggerSource triggerSource,
                                      Long auctionId, Long policyVersionId, BigDecimal rawPenaltyAmount,
                                      BigDecimal appliedPenaltyAmount, BigDecimal refundAmount, Long resolvedBy,
                                      OffsetDateTime resolvedAt, OffsetDateTime createdAt) {
        this(sellerDiagnosticCommitmentId, triggerSource, auctionId, policyVersionId, rawPenaltyAmount,
                appliedPenaltyAmount, refundAmount, resolvedBy, resolvedAt, createdAt);
        this.id = id;
    }

    private static void validateTriggerAuctionConsistency(TriggerSource triggerSource, Long auctionId) {
        boolean auctionRequired = triggerSource == TriggerSource.SALE_FAILED_C
                || triggerSource == TriggerSource.AUCTION_CANCELLED_C;
        if (auctionRequired && auctionId == null) {
            throw new IllegalArgumentException(triggerSource + " требует auctionId");
        }
        if (!auctionRequired && auctionId != null) {
            throw new IllegalArgumentException("DIAGNOSTIC_REJECTION не должен иметь auctionId");
        }
    }

    public Long getId() { return id; }
    public Long getSellerDiagnosticCommitmentId() { return sellerDiagnosticCommitmentId; }
    public TriggerSource getTriggerSource() { return triggerSource; }
    public Long getAuctionId() { return auctionId; }
    public Long getPolicyVersionId() { return policyVersionId; }
    public BigDecimal getRawPenaltyAmount() { return rawPenaltyAmount; }
    public BigDecimal getAppliedPenaltyAmount() { return appliedPenaltyAmount; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public Long getResolvedBy() { return resolvedBy; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
