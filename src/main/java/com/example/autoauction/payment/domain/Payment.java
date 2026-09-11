package com.example.autoauction.payment.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Slice 2 (Bank / Payment Infrastructure): one deposit top-up intent and its outcome.
 *
 * <p><b>Money-circuit separation (locked):</b> this aggregate concerns the buyer's participation
 * <i>deposit</i> only. It has no relationship to the Slice 1 winning-price {@code PaymentEvidence}
 * and never routes money toward a winning price.
 *
 * <p><b>Server-owned, immutable identity:</b> {@code id}, {@code userId}, {@code merchantReference},
 * {@code amountRequested}, {@code currency} and {@code createdAt} are fixed at creation. There is
 * no client-settable field on this aggregate. {@code expiresAt} is the one deliberate exception:
 * it starts at the platform's own 30-minute TTL and may only ever be shortened afterwards, by
 * {@link #shortenExpiryTo(OffsetDateTime)}, to apply the locked {@code effective expiry =
 * min(platform expiry, provider expiry)} rule - it can never be lengthened.
 *
 * <p><b>Write-once provider fields:</b> {@code providerTransactionId} and {@code amountConfirmed}
 * are assigned exactly once, by {@link #markSucceeded(String, BigDecimal)}, and can never be
 * replaced. Enforced here in the domain, not merely by the persistence mapping. {@code
 * providerReference} is likewise assigned exactly once, by {@link #markPending(String)} - it is
 * the provider's handle for this payment BEFORE a final transaction id exists, and is
 * independent from {@code providerTransactionId} (never required to equal it, never a
 * replacement for it).
 *
 * <p><b>Currency:</b> KGS only (MVP). There is deliberately no {@code Currency} abstraction
 * anywhere in this codebase; {@code currency} is a fixed {@code "KGS"} string and any other value
 * is rejected at construction.
 *
 * <p><b>Lifecycle</b> ({@link PaymentStatus}, guarded transitions only - no unrestricted setters):
 * <pre>
 *   CREATED  -> PENDING | FAILED | CANCELLED | EXPIRED
 *   PENDING  -> SUCCEEDED | FAILED | CANCELLED | EXPIRED
 *   EXPIRED  -> SUCCEEDED   (authenticated late-success recovery path only)
 * </pre>
 * Every other transition (e.g. {@code FAILED -> SUCCEEDED}, {@code CANCELLED -> SUCCEEDED},
 * {@code SUCCEEDED -> *}) is rejected with {@link IllegalStateException}.
 *
 * <p>Top-up amount limits (min / max / rolling-24h) are NOT enforced here - they belong to
 * {@link PaymentAmountPolicy}, matching this project's separation of aggregate invariants from
 * cross-aggregate policy.
 */
public class Payment {

    /** The only currency this MVP supports. */
    public static final String CURRENCY_KGS = "KGS";

    private Long id;
    private final Long userId;
    private final String merchantReference;
    private final BigDecimal amountRequested;
    private BigDecimal amountConfirmed;
    private final String currency;
    private PaymentStatus status;
    private String providerTransactionId;
    private String providerReference;
    private final String creationIdempotencyKey;
    private boolean lateSuccessAfterExpiry;
    private final OffsetDateTime createdAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime updatedAt;

    /**
     * Creates a new, not-yet-persisted intent in {@link PaymentStatus#CREATED}.
     *
     * @param creationIdempotencyKey optional client creation-retry key; may be {@code null}
     */
    public Payment(Long userId, String merchantReference, BigDecimal amountRequested,
                   String creationIdempotencyKey, OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        this.userId = Objects.requireNonNull(userId, "userId не может быть null");
        this.merchantReference = requireNonBlank(merchantReference, "merchantReference не может быть пустым");
        this.amountRequested = requirePositive(amountRequested, "amountRequested");
        this.currency = CURRENCY_KGS;
        this.creationIdempotencyKey = creationIdempotencyKey;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt не может быть null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt не может быть null");
        this.updatedAt = createdAt;
        this.status = PaymentStatus.CREATED;
        this.lateSuccessAfterExpiry = false;
    }

    /**
     * Reconstruction constructor - used exclusively by the persistence mapper to rebuild a
     * Payment already known to the database. Never used to create a new intent. Validates the
     * currency invariant defensively (a row must never carry a non-KGS currency).
     */
    public Payment(Long id, Long userId, String merchantReference, BigDecimal amountRequested,
                   BigDecimal amountConfirmed, String currency, PaymentStatus status,
                   String providerTransactionId, String providerReference, String creationIdempotencyKey,
                   boolean lateSuccessAfterExpiry, OffsetDateTime createdAt, OffsetDateTime expiresAt,
                   OffsetDateTime updatedAt) {
        this.id = id;
        this.userId = userId;
        this.merchantReference = merchantReference;
        this.amountRequested = amountRequested;
        this.amountConfirmed = amountConfirmed;
        if (!CURRENCY_KGS.equals(currency)) {
            throw new IllegalStateException("Payment " + id + " имеет недопустимую валюту: " + currency);
        }
        this.currency = currency;
        this.status = status;
        this.providerTransactionId = providerTransactionId;
        this.providerReference = providerReference;
        this.creationIdempotencyKey = creationIdempotencyKey;
        this.lateSuccessAfterExpiry = lateSuccessAfterExpiry;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.updatedAt = updatedAt;
    }

    // ── Guarded state transitions ────────────────────────────────────────────

    /**
     * {@code CREATED -> PENDING}. Binds {@code providerReference} - the provider's handle for
     * this payment, obtained when the provider accepted the creation request - exactly once, at
     * this transition. A Payment can never become PENDING without one: the locked creation design
     * only reaches this transition when the provider has already accepted the request and
     * returned a reference; a synchronous rejection goes to {@link #markFailed()} instead.
     *
     * <p>A second invocation (this Payment is no longer {@code CREATED}) is rejected by the same
     * {@link #requireStatus} guard used by every other transition, before any field is touched -
     * there is no separate "already assigned" check, because this transition has exactly one
     * entry point and the guard already makes re-entry structurally impossible, same/different
     * value alike.
     *
     * @throws IllegalStateException   if the current status is not CREATED
     * @throws IllegalArgumentException if providerReference is null or blank
     */
    public void markPending(String providerReference) {
        requireStatus("PENDING", PaymentStatus.CREATED);
        this.providerReference = requireNonBlank(providerReference, "providerReference не может быть пустым");
        this.status = PaymentStatus.PENDING;
        touch();
    }

    /**
     * {@code PENDING -> SUCCEEDED} or, for the authenticated late-success recovery path only,
     * {@code EXPIRED -> SUCCEEDED} (in which case {@link #isLateSuccessAfterExpiry()} becomes
     * {@code true}). Assigns the write-once provider fields.
     *
     * @throws IllegalStateException if the current status is not PENDING or EXPIRED, or if a
     *         provider transaction id / confirmed amount was already assigned
     */
    public void markSucceeded(String providerTransactionId, BigDecimal confirmedAmount) {
        // Validate every input into locals BEFORE mutating any field, so a bad argument can
        // never leave this aggregate half-transitioned (e.g. providerTransactionId assigned
        // but status still PENDING).
        if (this.status != PaymentStatus.PENDING && this.status != PaymentStatus.EXPIRED) {
            throw new IllegalStateException(
                    "Payment нельзя перевести в SUCCEEDED из статуса " + this.status
                            + " (допустимо только из PENDING или EXPIRED)");
        }
        if (this.providerTransactionId != null) {
            throw new IllegalStateException(
                    "providerTransactionId уже назначен и не может быть изменён: " + this.providerTransactionId);
        }
        if (this.amountConfirmed != null) {
            throw new IllegalStateException(
                    "amountConfirmed уже назначен и не может быть изменён: " + this.amountConfirmed);
        }
        String validatedTxnId = requireNonBlank(providerTransactionId, "providerTransactionId не может быть пустым");
        BigDecimal validatedAmount = requirePositive(confirmedAmount, "amountConfirmed");

        this.providerTransactionId = validatedTxnId;
        this.amountConfirmed = validatedAmount;
        if (this.status == PaymentStatus.EXPIRED) {
            this.lateSuccessAfterExpiry = true;
        }
        this.status = PaymentStatus.SUCCEEDED;
        touch();
    }

    /** {@code CREATED|PENDING -> FAILED}. */
    public void markFailed() {
        requireStatus("FAILED", PaymentStatus.CREATED, PaymentStatus.PENDING);
        this.status = PaymentStatus.FAILED;
        touch();
    }

    /** {@code CREATED|PENDING -> CANCELLED}. */
    public void markCancelled() {
        requireStatus("CANCELLED", PaymentStatus.CREATED, PaymentStatus.PENDING);
        this.status = PaymentStatus.CANCELLED;
        touch();
    }

    /** {@code CREATED|PENDING -> EXPIRED}. */
    public void markExpired() {
        requireStatus("EXPIRED", PaymentStatus.CREATED, PaymentStatus.PENDING);
        this.status = PaymentStatus.EXPIRED;
        touch();
    }

    /**
     * Shortens the effective expiry to {@code providerExpiresAt} if it is earlier than the
     * current {@link #expiresAt}; a value equal to the current expiry is accepted as a no-op
     * (does not call {@link #touch()} - no actual change occurred); a later value is rejected.
     * This operation can never extend expiry - it exists only to apply the locked {@code
     * effective expiry = min(platform expiry, provider expiry)} rule. Callable only while this
     * Payment is {@link #isNonTerminal() non-terminal} (CREATED or PENDING); EXPIRED is itself
     * terminal for this operation even though it can later still reach SUCCEEDED via the
     * late-success path.
     *
     * @throws NullPointerException  if providerExpiresAt is null
     * @throws IllegalStateException if this Payment is terminal
     * @throws IllegalArgumentException if providerExpiresAt is later than the current expiresAt
     */
    public void shortenExpiryTo(OffsetDateTime providerExpiresAt) {
        Objects.requireNonNull(providerExpiresAt, "providerExpiresAt не может быть null");
        if (!isNonTerminal()) {
            throw new IllegalStateException(
                    "Нельзя изменить срок действия Payment в статусе " + this.status);
        }
        if (providerExpiresAt.isAfter(this.expiresAt)) {
            throw new IllegalArgumentException(
                    "providerExpiresAt не может увеличивать срок действия: текущий=" + this.expiresAt
                            + ", предложенный=" + providerExpiresAt);
        }
        if (providerExpiresAt.isBefore(this.expiresAt)) {
            this.expiresAt = providerExpiresAt;
            touch();
        }
        // providerExpiresAt equal to the current value: no-op, intentionally no touch().
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getMerchantReference() { return merchantReference; }
    public BigDecimal getAmountRequested() { return amountRequested; }
    public BigDecimal getAmountConfirmed() { return amountConfirmed; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public String getProviderReference() { return providerReference; }
    public String getCreationIdempotencyKey() { return creationIdempotencyKey; }
    public boolean isLateSuccessAfterExpiry() { return lateSuccessAfterExpiry; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    /** {@code true} while the payment is still {@link PaymentStatus#CREATED} or {@link PaymentStatus#PENDING}. */
    public boolean isNonTerminal() {
        return status == PaymentStatus.CREATED || status == PaymentStatus.PENDING;
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    private void requireStatus(String targetLabel, PaymentStatus... allowed) {
        for (PaymentStatus s : allowed) {
            if (this.status == s) return;
        }
        throw new IllegalStateException(
                "Payment нельзя перевести в " + targetLabel + " из статуса " + this.status);
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static BigDecimal requirePositive(BigDecimal value, String field) {
        Objects.requireNonNull(value, field + " не может быть null");
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(field + " должен быть больше 0: " + value);
        }
        return value;
    }
}
