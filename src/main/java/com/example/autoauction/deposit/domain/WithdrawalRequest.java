package com.example.autoauction.deposit.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * F6: a user's request to withdraw money from the deposit, paid out manually by AlSo until a
 * bank is connected. Holds only the lifecycle and its audit trail - the money itself moves
 * exclusively through {@link DepositTransaction} rows (see {@code WithdrawalRequestService}).
 *
 * <p>Transitions: {@link #startProcessing} PENDING -> PROCESSING, {@link #markPaid}
 * PROCESSING -> PAID, {@link #reject} PENDING -> REJECTED. Every other transition, including a
 * repeat of the same one, is refused with {@link IllegalStateException}.
 */
public class WithdrawalRequest {

    public static final int MAX_REJECTION_REASON_LENGTH = 1000;

    private Long id;
    private final Long userId;
    private final BigDecimal amount;
    private WithdrawalRequestStatus status;
    private final String idempotencyKey;
    private final OffsetDateTime createdAt;
    private OffsetDateTime processingStartedAt;
    private Long processingStartedBy;
    private OffsetDateTime processedAt;
    private Long processedBy;
    private String rejectionReason;
    private Long version;

    /** A new request in {@link WithdrawalRequestStatus#PENDING}. */
    public WithdrawalRequest(Long userId, BigDecimal amount, String idempotencyKey, OffsetDateTime createdAt) {
        this.userId = Objects.requireNonNull(userId, "userId не может быть null");
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Сумма вывода должна быть больше 0");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Сумма не может содержать больше двух знаков после запятой");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Ключ идемпотентности обязателен для вывода средств");
        }
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt не может быть null");
        this.status = WithdrawalRequestStatus.PENDING;
        this.version = 0L;
    }

    /** Reconstruction constructor - persistence adapter only. */
    public WithdrawalRequest(Long id, Long userId, BigDecimal amount, WithdrawalRequestStatus status,
                             String idempotencyKey, OffsetDateTime createdAt,
                             OffsetDateTime processingStartedAt, Long processingStartedBy,
                             OffsetDateTime processedAt, Long processedBy, String rejectionReason, Long version) {
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
        this.version = version;
    }

    /** PENDING -> PROCESSING: an admin takes the request into work. No money moves. */
    public void startProcessing(Long adminId, OffsetDateTime at) {
        requireStatus(WithdrawalRequestStatus.PENDING, "взять в работу");
        this.processingStartedBy = Objects.requireNonNull(adminId, "adminId не может быть null");
        this.processingStartedAt = Objects.requireNonNull(at, "at не может быть null");
        this.status = WithdrawalRequestStatus.PROCESSING;
    }

    /** PROCESSING -> PAID: an admin confirms the manual payout was made. No money moves. */
    public void markPaid(Long adminId, OffsetDateTime at) {
        requireStatus(WithdrawalRequestStatus.PROCESSING, "отметить выплаченной");
        this.processedBy = Objects.requireNonNull(adminId, "adminId не может быть null");
        this.processedAt = Objects.requireNonNull(at, "at не может быть null");
        this.status = WithdrawalRequestStatus.PAID;
    }

    /**
     * PENDING -> REJECTED with a mandatory reason. The caller must return the amount to the
     * deposit in the same transaction.
     */
    public void reject(Long adminId, String reason, OffsetDateTime at) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Причина отклонения обязательна");
        }
        if (trimmed.length() > MAX_REJECTION_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    "Причина отклонения не может быть длиннее " + MAX_REJECTION_REASON_LENGTH + " символов");
        }
        requireStatus(WithdrawalRequestStatus.PENDING, "отклонить");
        this.processedBy = Objects.requireNonNull(adminId, "adminId не может быть null");
        this.processedAt = Objects.requireNonNull(at, "at не может быть null");
        this.rejectionReason = trimmed;
        this.status = WithdrawalRequestStatus.REJECTED;
    }

    private void requireStatus(WithdrawalRequestStatus required, String action) {
        if (status != required) {
            throw new IllegalStateException(
                    "Заявку на вывод #" + id + " в статусе " + status + " нельзя " + action);
        }
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
    public Long getVersion() { return version; }
}
