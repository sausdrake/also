package com.example.autoauction.sale.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * X1 (Sale Evidence + Sale Resolution foundation): immutable, append-only fact - "vehicle
 * {@link #vehicleId} and its documents, for auction {@link #auctionId}, were handed over to
 * buyer {@link #buyerId}, confirmed by admin {@link #confirmedByAdminId} at
 * {@link #confirmedAt}."
 *
 * Deliberately has no setters - same reasoning as {@link PaymentEvidence}.
 *
 * Deliberately has no sellerId: {@code Vehicle.diagnosticId} is NOT a uniformly authoritative
 * seller identity across every Vehicle-creation path in this codebase (confirmed by tracing
 * both VehicleService.createFromDiagnosticRequest, where it genuinely is the real submitting
 * owner's id, and DiagnosticVehicleController.createDraft, where it is simply whoever calls
 * that endpoint - potentially a technician, not the owner). Recording it here would sometimes
 * silently record the wrong person. Resolving seller identity is explicitly out of scope for
 * X1 and must not be invented here.
 *
 * Deliberately has no separate vehicleHandedOver/documentsHandedOver booleans: this is an
 * immutable fact, not a workflow checklist - the row's mere existence already means both
 * happened, confirmed, at this instant. Two booleans that would always both be true by the time
 * the row exists carry no information.
 */
public class HandoverEvidence {

    private Long id;
    private final Long auctionId;
    private final Long vehicleId;
    private final Long buyerId;
    private final Long confirmedByAdminId;
    private final OffsetDateTime confirmedAt;
    private final OffsetDateTime createdAt;

    /**
     * Creates a new, not-yet-persisted HandoverEvidence fact.
     */
    public HandoverEvidence(Long auctionId, Long vehicleId, Long buyerId, Long confirmedByAdminId,
                             OffsetDateTime confirmedAt, OffsetDateTime createdAt) {
        Objects.requireNonNull(auctionId, "auctionId не может быть null");
        Objects.requireNonNull(vehicleId, "vehicleId не может быть null");
        Objects.requireNonNull(buyerId, "buyerId не может быть null");
        Objects.requireNonNull(confirmedByAdminId, "confirmedByAdminId не может быть null");
        Objects.requireNonNull(confirmedAt, "confirmedAt не может быть null");
        Objects.requireNonNull(createdAt, "createdAt не может быть null");
        this.auctionId = auctionId;
        this.vehicleId = vehicleId;
        this.buyerId = buyerId;
        this.confirmedByAdminId = confirmedByAdminId;
        this.confirmedAt = confirmedAt;
        this.createdAt = createdAt;
    }

    /**
     * Reconstruction constructor, used exclusively by the persistence mapper.
     */
    public HandoverEvidence(Long id, Long auctionId, Long vehicleId, Long buyerId,
                             Long confirmedByAdminId, OffsetDateTime confirmedAt, OffsetDateTime createdAt) {
        this(auctionId, vehicleId, buyerId, confirmedByAdminId, confirmedAt, createdAt);
        this.id = id;
    }

    public Long getId() { return id; }
    public Long getAuctionId() { return auctionId; }
    public Long getVehicleId() { return vehicleId; }
    public Long getBuyerId() { return buyerId; }
    public Long getConfirmedByAdminId() { return confirmedByAdminId; }
    public OffsetDateTime getConfirmedAt() { return confirmedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
