package com.example.autoauction.payment.infrastructure.persistence;

import com.example.autoauction.payment.domain.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Slice 2: JPA representation of {@code payment} (V42).
 *
 * <p>{@code unique = true} on {@code provider_transaction_id} and {@code creation_idempotency_key}
 * drives Hibernate's own ddl-auto schema generation for the H2 test profile so those tests
 * exercise the same uniqueness invariant that V42's partial unique indexes
 * ({@code WHERE ... IS NOT NULL}) establish in production. Standard SQL never treats two NULLs as
 * duplicates, so a plain unique index is functionally equivalent for the only property that
 * matters here - the same reasoning already applied to
 * {@code deposit_transactions.idempotency_key} (V21). Flyway remains the sole production schema
 * authority.
 */
@Entity
@Table(name = "payment",
        uniqueConstraints = @UniqueConstraint(name = "uk_payment_merchant_reference", columnNames = "merchant_reference"))
public class JpaPaymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "merchant_reference", nullable = false, length = 64)
    private String merchantReference;

    @Column(name = "amount_requested", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountRequested;

    @Column(name = "amount_confirmed", precision = 19, scale = 2)
    private BigDecimal amountConfirmed;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentStatus status;

    @Column(name = "provider_transaction_id", length = 128, unique = true)
    private String providerTransactionId;

    @Column(name = "provider_reference", length = 128)
    private String providerReference;

    @Column(name = "creation_idempotency_key", length = 100, unique = true)
    private String creationIdempotencyKey;

    @Column(name = "late_success_after_expiry", nullable = false)
    private boolean lateSuccessAfterExpiry;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected JpaPaymentEntity() {
    }

    public JpaPaymentEntity(Long id, Long userId, String merchantReference, BigDecimal amountRequested,
                            BigDecimal amountConfirmed, String currency, PaymentStatus status,
                            String providerTransactionId, String providerReference, String creationIdempotencyKey,
                            boolean lateSuccessAfterExpiry, OffsetDateTime createdAt,
                            OffsetDateTime expiresAt, OffsetDateTime updatedAt) {
        this.id = id;
        this.userId = userId;
        this.merchantReference = merchantReference;
        this.amountRequested = amountRequested;
        this.amountConfirmed = amountConfirmed;
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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getMerchantReference() { return merchantReference; }
    public void setMerchantReference(String merchantReference) { this.merchantReference = merchantReference; }
    public BigDecimal getAmountRequested() { return amountRequested; }
    public void setAmountRequested(BigDecimal amountRequested) { this.amountRequested = amountRequested; }
    public BigDecimal getAmountConfirmed() { return amountConfirmed; }
    public void setAmountConfirmed(BigDecimal amountConfirmed) { this.amountConfirmed = amountConfirmed; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public void setProviderTransactionId(String providerTransactionId) { this.providerTransactionId = providerTransactionId; }
    public String getProviderReference() { return providerReference; }
    public void setProviderReference(String providerReference) { this.providerReference = providerReference; }
    public String getCreationIdempotencyKey() { return creationIdempotencyKey; }
    public void setCreationIdempotencyKey(String creationIdempotencyKey) { this.creationIdempotencyKey = creationIdempotencyKey; }
    public boolean isLateSuccessAfterExpiry() { return lateSuccessAfterExpiry; }
    public void setLateSuccessAfterExpiry(boolean lateSuccessAfterExpiry) { this.lateSuccessAfterExpiry = lateSuccessAfterExpiry; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
