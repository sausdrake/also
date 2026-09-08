package com.example.autoauction.sale.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * X1 (Sale Evidence + Sale Resolution foundation): the durable, 1:1-with-Auction record that
 * connects a successful-sale result with its payment/handover evidence and a separate,
 * downstream financial-processing signal.
 *
 * Immutable-after-creation: {@link #auctionId}, {@link #result}, {@link #reason},
 * {@link #resolvedBy}, {@link #resolvedAt}, {@link #paymentEvidenceId},
 * {@link #handoverEvidenceId}, {@link #createdAt}. The one deliberate exception is
 * {@link #financialResolutionStatus} - locked as NOT an Auction lifecycle state and explicitly
 * mutable, transitioned only through the guarded methods below, mirroring
 * SellerDiagnosticCommitment's own established shape (immutable snapshot fields + one mutable
 * lifecycle field, transitioned via a dedicated claim-first repository method, never a generic
 * whole-row update).
 *
 * X1 scope: only {@link SaleResolutionResult#SUCCESS} is creatable (locked decision 5) - which
 * is why the creation constructor below requires both evidence ids non-null unconditionally,
 * rather than branching on result the way a future fault-outcome slice would need to.
 *
 * Creating a SaleResolution does NOT change Auction status - that authoritative
 * WAITING_FOR_PAYMENT -&gt; SOLD transition belongs to a later slice (X2), which is expected to
 * treat a SaleResolution's mere existence as one of its own preconditions, not the other way
 * around.
 */
public class SaleResolution {

    private Long id;
    private final Long auctionId;
    private final SaleResolutionResult result;
    private final String reason;
    private final Long resolvedBy;
    private final OffsetDateTime resolvedAt;
    private final Long paymentEvidenceId;
    private final Long handoverEvidenceId;
    private FinancialResolutionStatus financialResolutionStatus;
    private final OffsetDateTime createdAt;

    /**
     * Creates a new, not-yet-persisted SUCCESS SaleResolution. financialResolutionStatus always
     * starts PENDING - downstream financial processing (deposit release, commission, seller
     * commitment finalization) is explicitly out of scope for X1 and is never triggered here.
     */
    public SaleResolution(Long auctionId, Long resolvedBy, OffsetDateTime resolvedAt,
                           Long paymentEvidenceId, Long handoverEvidenceId, OffsetDateTime createdAt) {
        this(null, auctionId, SaleResolutionResult.SUCCESS, null, resolvedBy, resolvedAt,
                paymentEvidenceId, handoverEvidenceId, FinancialResolutionStatus.PENDING, createdAt);
    }

    /**
     * Reconstruction constructor, used exclusively by the persistence mapper. Re-validates the
     * SUCCESS-requires-both-evidence invariant on every rebuild too, not just at creation - a
     * row that somehow violated it (e.g. corrupted data) must never be silently accepted as a
     * valid in-memory SaleResolution.
     */
    public SaleResolution(Long id, Long auctionId, SaleResolutionResult result, String reason,
                           Long resolvedBy, OffsetDateTime resolvedAt, Long paymentEvidenceId,
                           Long handoverEvidenceId, FinancialResolutionStatus financialResolutionStatus,
                           OffsetDateTime createdAt) {
        Objects.requireNonNull(auctionId, "auctionId не может быть null");
        Objects.requireNonNull(result, "result не может быть null");
        Objects.requireNonNull(resolvedBy, "resolvedBy не может быть null");
        Objects.requireNonNull(resolvedAt, "resolvedAt не может быть null");
        Objects.requireNonNull(financialResolutionStatus, "financialResolutionStatus не может быть null");
        Objects.requireNonNull(createdAt, "createdAt не может быть null");
        if (result == SaleResolutionResult.SUCCESS
                && (paymentEvidenceId == null || handoverEvidenceId == null)) {
            throw new IllegalArgumentException(
                    "SUCCESS требует paymentEvidenceId и handoverEvidenceId одновременно");
        }
        this.id = id;
        this.auctionId = auctionId;
        this.result = result;
        this.reason = reason;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = resolvedAt;
        this.paymentEvidenceId = paymentEvidenceId;
        this.handoverEvidenceId = handoverEvidenceId;
        this.financialResolutionStatus = financialResolutionStatus;
        this.createdAt = createdAt;
    }

    /**
     * PENDING -&gt; DONE only. Mirrors the persistence-layer claim-first UPDATE's own WHERE
     * clause - this in-memory guard exists for domain-level testability and defense in depth,
     * not as the actual concurrency-safety mechanism (that is the repository's job).
     */
    public void markFinancialResolutionDone() {
        if (this.financialResolutionStatus != FinancialResolutionStatus.PENDING) {
            throw new IllegalStateException(
                    "Перевести в DONE можно только из PENDING, текущий статус: " + this.financialResolutionStatus);
        }
        this.financialResolutionStatus = FinancialResolutionStatus.DONE;
    }

    /** PENDING -&gt; FAILED only. */
    public void markFinancialResolutionFailed() {
        if (this.financialResolutionStatus != FinancialResolutionStatus.PENDING) {
            throw new IllegalStateException(
                    "Перевести в FAILED можно только из PENDING, текущий статус: " + this.financialResolutionStatus);
        }
        this.financialResolutionStatus = FinancialResolutionStatus.FAILED;
    }

    /** FAILED -&gt; PENDING only - an explicit, manual reset, never automatic (see FinancialResolutionStatus). */
    public void resetFinancialResolutionToPending() {
        if (this.financialResolutionStatus != FinancialResolutionStatus.FAILED) {
            throw new IllegalStateException(
                    "Сбросить в PENDING можно только из FAILED, текущий статус: " + this.financialResolutionStatus);
        }
        this.financialResolutionStatus = FinancialResolutionStatus.PENDING;
    }

    public Long getId() { return id; }
    public Long getAuctionId() { return auctionId; }
    public SaleResolutionResult getResult() { return result; }
    public String getReason() { return reason; }
    public Long getResolvedBy() { return resolvedBy; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public Long getPaymentEvidenceId() { return paymentEvidenceId; }
    public Long getHandoverEvidenceId() { return handoverEvidenceId; }
    public FinancialResolutionStatus getFinancialResolutionStatus() { return financialResolutionStatus; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
