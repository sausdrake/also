package com.example.autoauction.auction.infrastructure.persistence;

import com.example.autoauction.auction.domain.PaymentDeadlineChangeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Slice 1B: JPA representation of {@code payment_deadline_history} (V46).
 */
@Entity
@Table(name = "payment_deadline_history")
public class JpaPaymentDeadlineHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "auction_id", nullable = false)
    private Long auctionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 24)
    private PaymentDeadlineChangeType changeType;

    @Column(name = "previous_deadline")
    private OffsetDateTime previousDeadline;

    @Column(name = "new_deadline", nullable = false)
    private OffsetDateTime newDeadline;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Column(name = "changed_by_admin_id")
    private Long changedByAdminId;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;

    protected JpaPaymentDeadlineHistoryEntity() {
    }

    public JpaPaymentDeadlineHistoryEntity(Long id, Long auctionId, PaymentDeadlineChangeType changeType,
                                            OffsetDateTime previousDeadline, OffsetDateTime newDeadline,
                                            String reason, Long changedByAdminId, OffsetDateTime changedAt) {
        this.id = id;
        this.auctionId = auctionId;
        this.changeType = changeType;
        this.previousDeadline = previousDeadline;
        this.newDeadline = newDeadline;
        this.reason = reason;
        this.changedByAdminId = changedByAdminId;
        this.changedAt = changedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public PaymentDeadlineChangeType getChangeType() { return changeType; }
    public void setChangeType(PaymentDeadlineChangeType changeType) { this.changeType = changeType; }
    public OffsetDateTime getPreviousDeadline() { return previousDeadline; }
    public void setPreviousDeadline(OffsetDateTime previousDeadline) { this.previousDeadline = previousDeadline; }
    public OffsetDateTime getNewDeadline() { return newDeadline; }
    public void setNewDeadline(OffsetDateTime newDeadline) { this.newDeadline = newDeadline; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getChangedByAdminId() { return changedByAdminId; }
    public void setChangedByAdminId(Long changedByAdminId) { this.changedByAdminId = changedByAdminId; }
    public OffsetDateTime getChangedAt() { return changedAt; }
    public void setChangedAt(OffsetDateTime changedAt) { this.changedAt = changedAt; }
}
