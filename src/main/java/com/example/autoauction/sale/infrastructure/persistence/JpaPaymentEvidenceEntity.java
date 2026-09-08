package com.example.autoauction.sale.infrastructure.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * auction_id is a plain Long, not a {@code @ManyToOne}/{@code @JoinColumn} - matching the
 * established convention already used by JpaSellerDiagnosticCommitmentEntity's own
 * diagnosticRequestId (see that class's own Javadoc for the identical reasoning). The real FK
 * to auctions(id) is declared in V34's migration SQL; it is provable only against the actual
 * Flyway-migrated (Postgres) schema, not under this project's test profile
 * (ddl-auto=create-drop, Flyway disabled - see application-test.properties). The UNIQUE
 * constraint and the amount CHECK below, by contrast, ARE mirrored at the JPA-annotation level
 * (via {@code unique = true} and {@code @Check}) specifically because Hibernate auto-creates
 * both under H2 from these annotations - unlike a cross-table FK, which it only ever creates
 * from a relationship mapping this entity deliberately does not declare.
 */
@Entity
@Table(name = "payment_evidence")
@Check(constraints = "amount > 0")
public class JpaPaymentEvidenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "auction_id", nullable = false, unique = true)
    private Long auctionId;

    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "received_by_admin_id", nullable = false)
    private Long receivedByAdminId;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected JpaPaymentEvidenceEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public Long getBuyerId() { return buyerId; }
    public void setBuyerId(Long buyerId) { this.buyerId = buyerId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Long getReceivedByAdminId() { return receivedByAdminId; }
    public void setReceivedByAdminId(Long receivedByAdminId) { this.receivedByAdminId = receivedByAdminId; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(OffsetDateTime receivedAt) { this.receivedAt = receivedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
