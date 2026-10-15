package com.example.autoauction.deposit.infrastructure.persistence;

import com.example.autoauction.deposit.domain.WithdrawalRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** F6: JPA representation of {@code withdrawal_requests} (V55). */
@Entity
@Table(name = "withdrawal_requests")
public class JpaWithdrawalRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WithdrawalRequestStatus status;

    @Column(name = "idempotency_key", nullable = false, length = 100, unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "processing_started_at")
    private OffsetDateTime processingStartedAt;

    @Column(name = "processing_started_by")
    private Long processingStartedBy;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @Column(name = "processed_by")
    private Long processedBy;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Column(name = "payout_note", length = 1000)
    private String payoutNote;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected JpaWithdrawalRequestEntity() {
    }

    public JpaWithdrawalRequestEntity(Long id, Long userId, BigDecimal amount, WithdrawalRequestStatus status,
                                      String idempotencyKey, OffsetDateTime createdAt,
                                      OffsetDateTime processingStartedAt, Long processingStartedBy,
                                      OffsetDateTime processedAt, Long processedBy, String rejectionReason,
                                      String payoutNote, Long version) {
        this.id = id;
        this.userId = userId;
        this.amount = amount;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
        this.processingStartedAt = processingStartedAt;
        this.processingStartedBy = processingStartedBy;
        this.processedAt = processedAt;
        this.processedBy = processedBy;
        this.rejectionReason = rejectionReason;
        this.payoutNote = payoutNote;
        this.version = version;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public BigDecimal getAmount() { return amount; }
    public WithdrawalRequestStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getProcessingStartedAt() { return processingStartedAt; }
    public Long getProcessingStartedBy() { return processingStartedBy; }
    public OffsetDateTime getProcessedAt() { return processedAt; }
    public Long getProcessedBy() { return processedBy; }
    public String getRejectionReason() { return rejectionReason; }
    public String getPayoutNote() { return payoutNote; }
    public Long getVersion() { return version; }
}
