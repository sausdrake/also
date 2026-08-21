// diagnostic/commitment/infrastructure/persistence/JpaSellerDiagnosticCommitmentEntity.java
package com.example.autoauction.diagnostic.commitment.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * uniqueConstraints mirrors V28's uk_seller_diagnostic_commitment_request DB constraint at
 * the JPA-annotation level too (not just in the Flyway migration) - this is deliberate, not
 * decorative: it is the only way this exact invariant is verifiable under this project's
 * test profile (ddl-auto=create-drop, no Flyway - see application-test.properties), since
 * Hibernate only auto-creates constraints it knows about from annotations. The FK to
 * diagnostic_requests(id) is NOT similarly duplicated here as a @ManyToOne/@JoinColumn -
 * this entity deliberately keeps a plain Long id field, matching the established
 * convention already used by JpaDepositFrozenEntity's own deposit_id/auction_id fields -
 * so real FK enforcement remains provable only against the actual Flyway-migrated schema,
 * not under this test profile. See Phase 1 pre-change verification report, acceptance
 * criteria section, for the explicit, documented limitation.
 */
@Entity
@Table(name = "seller_diagnostic_commitment",
        uniqueConstraints = @UniqueConstraint(name = "uk_seller_diagnostic_commitment_request",
                columnNames = "diagnostic_request_id"))
public class JpaSellerDiagnosticCommitmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "diagnostic_request_id", nullable = false)
    private Long diagnosticRequestId;

    @Column(name = "deposit_id", nullable = false)
    private Long depositId;

    @Column(name = "committed_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal committedAmount;

    @Column(name = "frozen_at", nullable = false)
    private OffsetDateTime frozenAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected JpaSellerDiagnosticCommitmentEntity() {}

    public JpaSellerDiagnosticCommitmentEntity(Long diagnosticRequestId, Long depositId,
                                                BigDecimal committedAmount, OffsetDateTime frozenAt) {
        this.diagnosticRequestId = diagnosticRequestId;
        this.depositId = depositId;
        this.committedAmount = committedAmount;
        this.frozenAt = frozenAt;
        this.createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getDiagnosticRequestId() { return diagnosticRequestId; }
    public Long getDepositId() { return depositId; }
    public BigDecimal getCommittedAmount() { return committedAmount; }
    public OffsetDateTime getFrozenAt() { return frozenAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setDiagnosticRequestId(Long diagnosticRequestId) { this.diagnosticRequestId = diagnosticRequestId; }
    public void setDepositId(Long depositId) { this.depositId = depositId; }
    public void setCommittedAmount(BigDecimal committedAmount) { this.committedAmount = committedAmount; }
    public void setFrozenAt(OffsetDateTime frozenAt) { this.frozenAt = frozenAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
