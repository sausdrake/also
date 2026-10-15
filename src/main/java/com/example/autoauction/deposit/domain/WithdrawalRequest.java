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
 * PROCESSING -> PAID (with a mandatory payout note), {@link #reject} PENDING or PROCESSING ->
 * REJECTED (with a mandatory reason and the admin's explicit confirmation that no payout was
 * made). PAID and REJECTED are final: every other transition, including a repeat of the same
 * one, is refused with {@link IllegalStateException}.
 */
public class WithdrawalRequest {

    public static final int MAX_REJECTION_REASON_LENGTH = 1000;
    public static final int MAX_PAYOUT_NOTE_LENGTH = 1000;

    /**
     * O2: characters that print nothing - ASCII whitespace, NBSP and the other Unicode spaces,
     * the zero-width characters. A payout note (O2) or a rejection reason (WD-01) made only of these
     * is no text. The CHECKs of V57 (note) and V60 (reason) use the very same class, so the
     * database refuses what the domain refuses.
     */
    private static final java.util.regex.Pattern INVISIBLE_CHARS = java.util.regex.Pattern.compile(
            "[\\t\\n\\x0B\\f\\r \\u0085\\u00A0\\u1680\\u2000-\\u200B\\u2028\\u2029\\u202F\\u205F\\u3000\\u2060\\uFEFF]");

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
    /** F6.1: the admin's note on how the manual payout was made - set only on PAID. */
    private String payoutNote;
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

    /** PENDING -> PROCESSING: an admin takes the request into work. No money moves. */
    public void startProcessing(Long adminId, OffsetDateTime at) {
        requireStatus(WithdrawalRequestStatus.PENDING, "взять в работу");
        this.processingStartedBy = Objects.requireNonNull(adminId, "adminId не может быть null");
        this.processingStartedAt = Objects.requireNonNull(at, "at не может быть null");
        this.status = WithdrawalRequestStatus.PROCESSING;
    }

    /**
     * PROCESSING -> PAID: an admin confirms the manual payout was made and records how in a
     * mandatory note (part of the audit trail). No money moves.
     */
    public void markPaid(Long adminId, String payoutNote, OffsetDateTime at) {
        requireStatus(WithdrawalRequestStatus.PROCESSING, "отметить выплаченной");
        String note = payoutNote == null ? "" : payoutNote.trim();
        if (INVISIBLE_CHARS.matcher(note).replaceAll("").isEmpty()) {
            throw new IllegalArgumentException("Заметка о произведённой выплате обязательна");
        }
        if (note.length() > MAX_PAYOUT_NOTE_LENGTH) {
            throw new IllegalArgumentException(
                    "Заметка о выплате не может быть длиннее " + MAX_PAYOUT_NOTE_LENGTH + " символов");
        }
        this.processedBy = Objects.requireNonNull(adminId, "adminId не может быть null");
        this.processedAt = Objects.requireNonNull(at, "at не может быть null");
        this.payoutNote = note;
        this.status = WithdrawalRequestStatus.PAID;
    }

    /**
     * PENDING or PROCESSING -> REJECTED with a mandatory reason and the admin's explicit
     * confirmation that no payout was made - nothing else proves that, PROCESSING least of all.
     * A request rejected from PROCESSING keeps its processing_started_* audit fields. The caller
     * must return the amount to the deposit in the same transaction.
     */
    public void reject(Long adminId, String reason, boolean payoutNotMadeConfirmed, OffsetDateTime at) {
        if (status != WithdrawalRequestStatus.PENDING && status != WithdrawalRequestStatus.PROCESSING) {
            throw new IllegalStateException("Заявку на вывод #" + id + " в статусе " + status + " нельзя отклонить");
        }
        String trimmed = reason == null ? "" : reason.trim();
        if (INVISIBLE_CHARS.matcher(trimmed).replaceAll("").isEmpty()) {
            // WD-01: the user sees this reason - a reason that prints nothing is no reason
            throw new IllegalArgumentException("Причина отклонения обязательна");
        }
        if (trimmed.length() > MAX_REJECTION_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    "Причина отклонения не может быть длиннее " + MAX_REJECTION_REASON_LENGTH + " символов");
        }
        if (!payoutNotMadeConfirmed) {
            throw new IllegalArgumentException(
                    "Отклонение требует подтверждения, что выплата пользователю не производилась");
        }
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
    public String getPayoutNote() { return payoutNote; }
    public Long getVersion() { return version; }
}
