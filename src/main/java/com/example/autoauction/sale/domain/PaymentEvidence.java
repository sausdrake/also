package com.example.autoauction.sale.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * X1 (Sale Evidence + Sale Resolution foundation): immutable, append-only fact - "buyer
 * {@link #buyerId} paid {@link #amount} for auction {@link #auctionId}, received by admin
 * {@link #receivedByAdminId} at {@link #receivedAt}."
 *
 * Deliberately has no setters at all - every field is final, assigned once, either by the
 * creation constructor (a brand-new fact) or the reconstruction constructor (restoring an
 * already-persisted one). There is no "correction" or "update" operation - a materially wrong
 * PaymentEvidence row is a data-integrity problem for a human to resolve directly, not a
 * business operation this domain object exposes.
 *
 * Deliberately has no currency field (this codebase has no Currency abstraction anywhere - every
 * monetary field, on Auction included, is implicitly one currency) and no paymentMethod field
 * (MVP has exactly one method, CASH, locked - a single-value field records no information; add
 * it as a small additive column if and when a second method actually exists, not speculatively
 * now).
 */
public class PaymentEvidence {

    private Long id;
    private final Long auctionId;
    private final Long buyerId;
    private final BigDecimal amount;
    private final Long receivedByAdminId;
    private final OffsetDateTime receivedAt;
    private final OffsetDateTime createdAt;

    /**
     * Creates a new, not-yet-persisted PaymentEvidence fact.
     */
    public PaymentEvidence(Long auctionId, Long buyerId, BigDecimal amount, Long receivedByAdminId,
                            OffsetDateTime receivedAt, OffsetDateTime createdAt) {
        Objects.requireNonNull(auctionId, "auctionId не может быть null");
        Objects.requireNonNull(buyerId, "buyerId не может быть null");
        Objects.requireNonNull(amount, "amount не может быть null");
        Objects.requireNonNull(receivedByAdminId, "receivedByAdminId не может быть null");
        Objects.requireNonNull(receivedAt, "receivedAt не может быть null");
        Objects.requireNonNull(createdAt, "createdAt не может быть null");
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount должен быть больше 0: " + amount);
        }
        this.auctionId = auctionId;
        this.buyerId = buyerId;
        this.amount = amount.setScale(2, RoundingMode.HALF_UP);
        this.receivedByAdminId = receivedByAdminId;
        this.receivedAt = receivedAt;
        this.createdAt = createdAt;
    }

    /**
     * Reconstruction constructor, used exclusively by the persistence mapper to rebuild a
     * PaymentEvidence already known to the database. Never used to create a new fact - that is
     * the job of the constructor above, which enforces this domain's own validation.
     */
    public PaymentEvidence(Long id, Long auctionId, Long buyerId, BigDecimal amount,
                            Long receivedByAdminId, OffsetDateTime receivedAt, OffsetDateTime createdAt) {
        this(auctionId, buyerId, amount, receivedByAdminId, receivedAt, createdAt);
        this.id = id;
    }

    public Long getId() { return id; }
    public Long getAuctionId() { return auctionId; }
    public Long getBuyerId() { return buyerId; }
    public BigDecimal getAmount() { return amount; }
    public Long getReceivedByAdminId() { return receivedByAdminId; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
