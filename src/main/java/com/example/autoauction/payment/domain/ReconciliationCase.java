package com.example.autoauction.payment.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Slice 2: an operational anomaly attached to a {@link Payment}, kept entirely separate from the
 * payment lifecycle ({@link PaymentStatus} never takes an anomaly value).
 *
 * <p>A case is opened once (status {@link ReconciliationCaseStatus#OPEN}) and resolved once by an
 * admin. {@link #resolve(Long, String, OffsetDateTime)} on an already-resolved case is rejected.
 * There is at most one case of each {@link ReconciliationCaseType} per Payment (enforced by a DB
 * {@code UNIQUE(payment_id, type)}).
 */
public class ReconciliationCase {

    private Long id;
    private final Long paymentId;
    private final ReconciliationCaseType type;
    private ReconciliationCaseStatus status;
    private final OffsetDateTime openedAt;
    private Long resolvedByAdminId;
    private String resolutionNote;
    private OffsetDateTime resolvedAt;
    /**
     * Phase 17: optimistic-lock version - persistence metadata, not business state. {@code 0} for
     * a case that has never been saved; the persistence mapper overwrites this on every
     * load/reconstruction. No domain behavior reads or branches on this field.
     */
    private Long version;

    /** Opens a new case in {@link ReconciliationCaseStatus#OPEN}. */
    public ReconciliationCase(Long paymentId, ReconciliationCaseType type, OffsetDateTime openedAt) {
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId не может быть null");
        this.type = Objects.requireNonNull(type, "type не может быть null");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt не может быть null");
        this.status = ReconciliationCaseStatus.OPEN;
        this.version = 0L;
    }

    /** Reconstruction constructor - persistence mapper only. */
    public ReconciliationCase(Long id, Long paymentId, ReconciliationCaseType type,
                              ReconciliationCaseStatus status, OffsetDateTime openedAt,
                              Long resolvedByAdminId, String resolutionNote, OffsetDateTime resolvedAt,
                              Long version) {
        this.id = id;
        this.paymentId = paymentId;
        this.type = type;
        this.status = status;
        this.openedAt = openedAt;
        this.resolvedByAdminId = resolvedByAdminId;
        this.resolutionNote = resolutionNote;
        this.resolvedAt = resolvedAt;
        this.version = version;
    }

    /**
     * Marks this case resolved by the given admin.
     *
     * @throws IllegalStateException if the case is already resolved
     * @throws NullPointerException  if {@code adminId} or {@code at} is null
     */
    public void resolve(Long adminId, String note, OffsetDateTime at) {
        if (this.status == ReconciliationCaseStatus.RESOLVED) {
            throw new IllegalStateException(
                    "ReconciliationCase " + id + " уже разрешён и не может быть разрешён повторно");
        }
        this.resolvedByAdminId = Objects.requireNonNull(adminId, "adminId не может быть null");
        this.resolvedAt = Objects.requireNonNull(at, "at не может быть null");
        this.resolutionNote = note;
        this.status = ReconciliationCaseStatus.RESOLVED;
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public ReconciliationCaseType getType() { return type; }
    public ReconciliationCaseStatus getStatus() { return status; }
    public OffsetDateTime getOpenedAt() { return openedAt; }
    public Long getResolvedByAdminId() { return resolvedByAdminId; }
    public String getResolutionNote() { return resolutionNote; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public Long getVersion() { return version; }

    public boolean isOpen() { return status == ReconciliationCaseStatus.OPEN; }
}
