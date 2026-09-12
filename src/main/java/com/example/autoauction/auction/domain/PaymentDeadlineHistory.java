package com.example.autoauction.auction.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Slice 1B: an immutable, append-only record of one change to an {@link Auction}'s
 * {@code paymentDeadline} - either the system-generated {@link PaymentDeadlineChangeType#INITIAL}
 * row created atomically with the WAITING_FOR_PAYMENT transition, or an admin-driven
 * {@link PaymentDeadlineChangeType#ADMIN_EXTENSION}. Mirrors {@code SettlementCorrection}'s own
 * established append-only-fact shape in this codebase - never a mutation, never deleted.
 *
 * <p>Exactly one of the two creation paths below should ever be used; the raw constructor
 * enforces the actor/previous-value invariants tied to {@code changeType} in one place.
 */
public class PaymentDeadlineHistory {

    /**
     * Fixed, non-empty reason recorded for every {@link PaymentDeadlineChangeType#INITIAL} row -
     * the system has no admin-authored reason to record, and the locked business rule makes
     * {@code reason} mandatory even for this system-generated case.
     */
    public static final String SYSTEM_INITIAL_REASON =
            "Системный дедлайн, установленный автоматически при переходе в WAITING_FOR_PAYMENT";

    private Long id;
    private final Long auctionId;
    private final PaymentDeadlineChangeType changeType;
    private final OffsetDateTime previousDeadline;
    private final OffsetDateTime newDeadline;
    private final String reason;
    private final Long changedByAdminId;
    private final OffsetDateTime changedAt;

    /** Creates a new, not-yet-persisted history row. */
    public PaymentDeadlineHistory(Long auctionId, PaymentDeadlineChangeType changeType,
                                   OffsetDateTime previousDeadline, OffsetDateTime newDeadline,
                                   String reason, Long changedByAdminId, OffsetDateTime changedAt) {
        this.auctionId = Objects.requireNonNull(auctionId, "auctionId не может быть null");
        this.changeType = Objects.requireNonNull(changeType, "changeType не может быть null");
        this.newDeadline = Objects.requireNonNull(newDeadline, "newDeadline не может быть null");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason обязателен для записи истории платёжного дедлайна");
        }
        this.reason = reason;
        this.changedAt = Objects.requireNonNull(changedAt, "changedAt не может быть null");
        validateActorAndPreviousValue(changeType, previousDeadline, changedByAdminId);
        this.previousDeadline = previousDeadline;
        this.changedByAdminId = changedByAdminId;
    }

    /** Reconstruction constructor, used exclusively by the persistence mapper. */
    public PaymentDeadlineHistory(Long id, Long auctionId, PaymentDeadlineChangeType changeType,
                                   OffsetDateTime previousDeadline, OffsetDateTime newDeadline,
                                   String reason, Long changedByAdminId, OffsetDateTime changedAt) {
        this(auctionId, changeType, previousDeadline, newDeadline, reason, changedByAdminId, changedAt);
        this.id = id;
    }

    /**
     * The one authoritative changeType&lt;-&gt;actor/previous-value rule, mirroring
     * {@code SettlementCorrection.validateValueCombination}'s established pattern: INITIAL must
     * have no admin actor and no previous value (there is no "previous" before the first
     * deadline ever existed); ADMIN_EXTENSION must have both.
     */
    private static void validateActorAndPreviousValue(PaymentDeadlineChangeType changeType,
                                                        OffsetDateTime previousDeadline, Long changedByAdminId) {
        switch (changeType) {
            case INITIAL -> {
                if (changedByAdminId != null) {
                    throw new IllegalArgumentException("changedByAdminId должен быть null для INITIAL");
                }
                if (previousDeadline != null) {
                    throw new IllegalArgumentException("previousDeadline должен быть null для INITIAL");
                }
            }
            case ADMIN_EXTENSION -> {
                if (changedByAdminId == null) {
                    throw new IllegalArgumentException("changedByAdminId обязателен для ADMIN_EXTENSION");
                }
                if (previousDeadline == null) {
                    throw new IllegalArgumentException("previousDeadline обязателен для ADMIN_EXTENSION");
                }
            }
        }
    }

    /**
     * Factory for the system-generated initial deadline row, created atomically with the
     * WAITING_FOR_PAYMENT transition. Never takes an admin id - see {@link #SYSTEM_INITIAL_REASON}.
     */
    public static PaymentDeadlineHistory initial(Long auctionId, OffsetDateTime newDeadline, OffsetDateTime changedAt) {
        return new PaymentDeadlineHistory(auctionId, PaymentDeadlineChangeType.INITIAL, null, newDeadline,
                SYSTEM_INITIAL_REASON, null, changedAt);
    }

    /** Factory for an admin-driven override/extension. */
    public static PaymentDeadlineHistory adminExtension(Long auctionId, OffsetDateTime previousDeadline,
                                                          OffsetDateTime newDeadline, String reason,
                                                          Long changedByAdminId, OffsetDateTime changedAt) {
        return new PaymentDeadlineHistory(auctionId, PaymentDeadlineChangeType.ADMIN_EXTENSION, previousDeadline,
                newDeadline, reason, changedByAdminId, changedAt);
    }

    public Long getId() { return id; }
    public Long getAuctionId() { return auctionId; }
    public PaymentDeadlineChangeType getChangeType() { return changeType; }
    public OffsetDateTime getPreviousDeadline() { return previousDeadline; }
    public OffsetDateTime getNewDeadline() { return newDeadline; }
    public String getReason() { return reason; }
    public Long getChangedByAdminId() { return changedByAdminId; }
    public OffsetDateTime getChangedAt() { return changedAt; }
}
