package com.example.autoauction.sale.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Slice 1 (Sale Completion &amp; Failure API): immutable, append-only fact - "the full winning
 * price physically received under {@link PaymentEvidence} {@link #paymentEvidenceId} for auction
 * {@link #auctionId} was returned in full to buyer {@link #buyerId}, confirmed by admin
 * {@link #refundedByAdminId} at {@link #refundedAt}."
 *
 * Same immutability contract as {@link PaymentEvidence}/{@link HandoverEvidence}: every field is
 * final, assigned once, either by the creation constructor or the reconstruction constructor.
 * No setters, no correction/update operation, no mutable status. A materially wrong row is a
 * data-integrity problem for a human to resolve directly, not a business operation this domain
 * object exposes.
 *
 * Deliberately has no currency field - identical reasoning to {@link PaymentEvidence} (this
 * codebase has no Currency abstraction; every monetary field is implicitly one currency).
 *
 * Locked business rule: {@link #refundedAmount} always equals the referenced
 * {@link PaymentEvidence}'s own amount exactly. That equality is guaranteed by construction in
 * {@code PaymentRefundService} (the amount is read from the loaded PaymentEvidence, never
 * accepted as caller input), not merely validated here - this constructor only enforces the
 * type-level {@code > 0} floor shared with PaymentEvidence.
 */
public class PaymentRefundEvidence {

    private Long id;
    private final Long paymentEvidenceId;
    private final Long auctionId;
    private final Long buyerId;
    private final BigDecimal refundedAmount;
    private final Long refundedByAdminId;
    private final OffsetDateTime refundedAt;
    private final OffsetDateTime createdAt;

    /**
     * Creates a new, not-yet-persisted PaymentRefundEvidence fact.
     */
    public PaymentRefundEvidence(Long paymentEvidenceId, Long auctionId, Long buyerId, BigDecimal refundedAmount,
                                 Long refundedByAdminId, OffsetDateTime refundedAt, OffsetDateTime createdAt) {
        Objects.requireNonNull(paymentEvidenceId, "paymentEvidenceId не может быть null");
        Objects.requireNonNull(auctionId, "auctionId не может быть null");
        Objects.requireNonNull(buyerId, "buyerId не может быть null");
        Objects.requireNonNull(refundedAmount, "refundedAmount не может быть null");
        Objects.requireNonNull(refundedByAdminId, "refundedByAdminId не может быть null");
        Objects.requireNonNull(refundedAt, "refundedAt не может быть null");
        Objects.requireNonNull(createdAt, "createdAt не может быть null");
        if (refundedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("refundedAmount должен быть больше 0: " + refundedAmount);
        }
        this.paymentEvidenceId = paymentEvidenceId;
        this.auctionId = auctionId;
        this.buyerId = buyerId;
        this.refundedAmount = refundedAmount.setScale(2, RoundingMode.HALF_UP);
        this.refundedByAdminId = refundedByAdminId;
        this.refundedAt = refundedAt;
        this.createdAt = createdAt;
    }

    /**
     * Reconstruction constructor, used exclusively by the persistence mapper to rebuild a
     * PaymentRefundEvidence already known to the database.
     */
    public PaymentRefundEvidence(Long id, Long paymentEvidenceId, Long auctionId, Long buyerId,
                                 BigDecimal refundedAmount, Long refundedByAdminId, OffsetDateTime refundedAt,
                                 OffsetDateTime createdAt) {
        this(paymentEvidenceId, auctionId, buyerId, refundedAmount, refundedByAdminId, refundedAt, createdAt);
        this.id = id;
    }

    public Long getId() { return id; }
    public Long getPaymentEvidenceId() { return paymentEvidenceId; }
    public Long getAuctionId() { return auctionId; }
    public Long getBuyerId() { return buyerId; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public Long getRefundedByAdminId() { return refundedByAdminId; }
    public OffsetDateTime getRefundedAt() { return refundedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
