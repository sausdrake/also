package com.example.autoauction.sale.infrastructure.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * auction_id/vehicle_id are plain Long fields, not {@code @ManyToOne}/{@code @JoinColumn} - same
 * convention and same reasoning as JpaPaymentEvidenceEntity/JpaSellerDiagnosticCommitmentEntity.
 * Both real FKs (to auctions(id) and vehicles(id)) live in V34's migration SQL and are provable
 * only against the actual Flyway-migrated schema, not under this project's H2 test profile.
 */
@Entity
@Table(name = "handover_evidence")
public class JpaHandoverEvidenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "auction_id", nullable = false, unique = true)
    private Long auctionId;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;

    @Column(name = "confirmed_by_admin_id", nullable = false)
    private Long confirmedByAdminId;

    @Column(name = "confirmed_at", nullable = false)
    private OffsetDateTime confirmedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected JpaHandoverEvidenceEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public Long getBuyerId() { return buyerId; }
    public void setBuyerId(Long buyerId) { this.buyerId = buyerId; }
    public Long getConfirmedByAdminId() { return confirmedByAdminId; }
    public void setConfirmedByAdminId(Long confirmedByAdminId) { this.confirmedByAdminId = confirmedByAdminId; }
    public OffsetDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(OffsetDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
