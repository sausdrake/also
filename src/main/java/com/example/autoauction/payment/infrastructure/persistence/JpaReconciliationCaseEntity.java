package com.example.autoauction.payment.infrastructure.persistence;

import com.example.autoauction.payment.domain.ReconciliationCaseStatus;
import com.example.autoauction.payment.domain.ReconciliationCaseType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;

/**
 * Slice 2: JPA representation of {@code reconciliation_case} (V42). At most one case of each
 * {@link ReconciliationCaseType} per payment, enforced by {@code UNIQUE(payment_id, type)}.
 */
@Entity
@Table(name = "reconciliation_case",
        uniqueConstraints = @UniqueConstraint(name = "uk_reconciliation_case_payment_type",
                columnNames = {"payment_id", "type"}))
public class JpaReconciliationCaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 24)
    private ReconciliationCaseType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private ReconciliationCaseStatus status;

    @Column(name = "opened_at", nullable = false)
    private OffsetDateTime openedAt;

    @Column(name = "resolved_by_admin_id")
    private Long resolvedByAdminId;

    @Column(name = "resolution_note", length = 100_000)
    private String resolutionNote;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    protected JpaReconciliationCaseEntity() {
    }

    public JpaReconciliationCaseEntity(Long id, Long paymentId, ReconciliationCaseType type,
                                       ReconciliationCaseStatus status, OffsetDateTime openedAt,
                                       Long resolvedByAdminId, String resolutionNote, OffsetDateTime resolvedAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.type = type;
        this.status = status;
        this.openedAt = openedAt;
        this.resolvedByAdminId = resolvedByAdminId;
        this.resolutionNote = resolutionNote;
        this.resolvedAt = resolvedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }
    public ReconciliationCaseType getType() { return type; }
    public void setType(ReconciliationCaseType type) { this.type = type; }
    public ReconciliationCaseStatus getStatus() { return status; }
    public void setStatus(ReconciliationCaseStatus status) { this.status = status; }
    public OffsetDateTime getOpenedAt() { return openedAt; }
    public void setOpenedAt(OffsetDateTime openedAt) { this.openedAt = openedAt; }
    public Long getResolvedByAdminId() { return resolvedByAdminId; }
    public void setResolvedByAdminId(Long resolvedByAdminId) { this.resolvedByAdminId = resolvedByAdminId; }
    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String resolutionNote) { this.resolutionNote = resolutionNote; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
}
