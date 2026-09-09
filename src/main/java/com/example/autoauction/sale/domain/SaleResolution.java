package com.example.autoauction.sale.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * X1/X3-A (Sale Evidence + Sale Resolution foundation, extended with SALE_FAILED outcomes):
 * the durable, 1:1-with-Auction record of how an auction's sale concluded - either SUCCESS
 * (with its payment/handover evidence and downstream financial-processing signal) or one of
 * the four failed-sale outcomes (B, BUYER_FAULT, PLATFORM_FAULT, C).
 *
 * Immutable-after-creation: {@link #auctionId}, {@link #result}, {@link #reason},
 * {@link #faultParty}, {@link #resolvedBy}, {@link #resolvedAt}, {@link #paymentEvidenceId},
 * {@link #handoverEvidenceId}, {@link #createdAt}. The one deliberate exception is
 * {@link #financialResolutionStatus} - locked as NOT an Auction lifecycle state and explicitly
 * mutable, transitioned only through the guarded methods below, mirroring
 * SellerDiagnosticCommitment's own established shape (immutable snapshot fields + one mutable
 * lifecycle field, transitioned via a dedicated claim-first repository method, never a generic
 * whole-row update). X3-A does not drive financialResolutionStatus for any failure outcome -
 * it remains PENDING at creation exactly as it always has, and nothing here moves it further;
 * that stays exclusively SUCCESS's own future financial-completion concern.
 *
 * X3-A (SALE_FAILED outcomes): {@link #faultParty} and {@link #reason} (exposed to callers as
 * "failureReason" via the creation constructor below, since this is the same field/column that
 * has existed, always null, since X1 - no new column was added; see the X3-A architecture
 * fight for why) are new, both nullable. {@link #reason}/failureReason is strictly audit-only:
 * a human-readable narrative an admin supplies, never parsed or branched on by any code
 * anywhere in this codebase - it must never become a substitute for a future, properly
 * modeled violation-policy catalogue.
 *
 * Evidence ids remain exclusively a SUCCESS concept - every SALE_FAILED outcome created via
 * the failure constructor below always has both evidence ids null, by construction. Only
 * {@link SaleResolutionResult#SUCCESS} requires them (unchanged from X1 - see the existing
 * check below, not modified by this slice).
 *
 * Creating a SaleResolution does NOT change Auction status - the authoritative
 * WAITING_FOR_PAYMENT -&gt; SOLD/SALE_FAILED transition belongs to the orchestrator
 * (AuctionFinalizer.completeSale / SaleFailureResolutionOrchestrator.resolveSaleFailure), which
 * treats a SaleResolution's mere existence as one of its own preconditions, not the other way
 * around.
 */
public class SaleResolution {

    private Long id;
    private final Long auctionId;
    private final SaleResolutionResult result;
    private final String reason;
    private final FaultParty faultParty;
    private final Long resolvedBy;
    private final OffsetDateTime resolvedAt;
    private final Long paymentEvidenceId;
    private final Long handoverEvidenceId;
    private FinancialResolutionStatus financialResolutionStatus;
    private final OffsetDateTime createdAt;

    /**
     * Creates a new, not-yet-persisted SUCCESS SaleResolution. financialResolutionStatus always
     * starts PENDING - downstream financial processing (deposit release, commission, seller
     * commitment finalization) is explicitly out of scope for X1/X3-A and is never triggered
     * here. Unmodified by X3-A beyond threading a null faultParty through to the reconstruction
     * constructor below (SUCCESS always has faultParty == null).
     */
    public SaleResolution(Long auctionId, Long resolvedBy, OffsetDateTime resolvedAt,
                           Long paymentEvidenceId, Long handoverEvidenceId, OffsetDateTime createdAt) {
        this(null, auctionId, SaleResolutionResult.SUCCESS, null, null, resolvedBy, resolvedAt,
                paymentEvidenceId, handoverEvidenceId, FinancialResolutionStatus.PENDING, createdAt);
    }

    /**
     * X3-A: creates a new, not-yet-persisted SALE_FAILED outcome resolution - B, BUYER_FAULT,
     * PLATFORM_FAULT, or C (never SUCCESS; that remains the constructor above's exclusive job -
     * see SaleResolutionService.createFailure()'s own rejection of SUCCESS for the enforcement
     * point). Both evidence ids are always null - a failed sale has no payment/handover
     * evidence by definition. financialResolutionStatus starts PENDING for field-level
     * consistency, though nothing in X3-A ever transitions it further for a failure outcome.
     */
    public SaleResolution(Long auctionId, SaleResolutionResult result, FaultParty faultParty,
                           String failureReason, Long resolvedBy, OffsetDateTime resolvedAt,
                           OffsetDateTime createdAt) {
        this(null, auctionId, result, failureReason, faultParty, resolvedBy, resolvedAt,
                null, null, FinancialResolutionStatus.PENDING, createdAt);
    }

    /**
     * Reconstruction constructor, used exclusively by the persistence mapper. Re-validates the
     * SUCCESS-requires-both-evidence invariant, and (X3-A) the result&lt;-&gt;faultParty
     * consistency invariant, on every rebuild too, not just at creation - a row that somehow
     * violated either (e.g. corrupted data) must never be silently accepted as a valid
     * in-memory SaleResolution.
     */
    public SaleResolution(Long id, Long auctionId, SaleResolutionResult result, String reason, FaultParty faultParty,
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
        validateFaultPartyConsistency(result, faultParty);
        this.id = id;
        this.auctionId = auctionId;
        this.result = result;
        this.reason = reason;
        this.faultParty = faultParty;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = resolvedAt;
        this.paymentEvidenceId = paymentEvidenceId;
        this.handoverEvidenceId = handoverEvidenceId;
        this.financialResolutionStatus = financialResolutionStatus;
        this.createdAt = createdAt;
    }

    /**
     * X3-A: the one authoritative result&lt;-&gt;faultParty consistency rule (locked decision -
     * not scattered across callers). SUCCESS and B are blameless (faultParty must be null);
     * BUYER_FAULT/PLATFORM_FAULT/C each require their own single matching FaultParty value.
     * Enforced in the reconstruction constructor, the one point every construction path
     * (both creation constructors above, plus every rebuild from the database via the
     * persistence mapper) converges through - never duplicated at any call site, and mirrored
     * (not duplicated in meaning, just enforced a second time for defense-in-depth) by the DB
     * CHECK constraint added in V35.
     */
    private static void validateFaultPartyConsistency(SaleResolutionResult result, FaultParty faultParty) {
        FaultParty expected = switch (result) {
            case SUCCESS, B -> null;
            case BUYER_FAULT -> FaultParty.BUYER;
            case PLATFORM_FAULT -> FaultParty.PLATFORM;
            case C -> FaultParty.SELLER;
        };
        if (expected != faultParty) {
            throw new IllegalArgumentException(
                    "faultParty (" + faultParty + ") не соответствует result (" + result + "); ожидается: " + expected);
        }
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
    public FaultParty getFaultParty() { return faultParty; }
    public Long getResolvedBy() { return resolvedBy; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public Long getPaymentEvidenceId() { return paymentEvidenceId; }
    public Long getHandoverEvidenceId() { return handoverEvidenceId; }
    public FinancialResolutionStatus getFinancialResolutionStatus() { return financialResolutionStatus; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
