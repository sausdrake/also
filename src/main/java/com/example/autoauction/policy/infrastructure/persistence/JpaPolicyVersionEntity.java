package com.example.autoauction.policy.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "policy_version")
public class JpaPolicyVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Allocated by {@link PolicyVersionRepositoryAdapter} from the single-row
     * policy_version_counter table (under a pessimistic row lock) before insert - not a JPA
     * @GeneratedValue, since standard JPA/Hibernate only reliably auto-populates the @Id
     * attribute this way, not an arbitrary non-id column. updatable = false: this value is
     * never changed after insert.
     */
    @Column(name = "version_number", nullable = false, updatable = false)
    private Long versionNumber;

    @Column(name = "commission_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal commissionRate;

    @Column(name = "published", nullable = false)
    private boolean published;

    @Column(name = "effective_from", nullable = false)
    private OffsetDateTime effectiveFrom;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getVersionNumber() { return versionNumber; }
    public void setVersionNumber(Long versionNumber) { this.versionNumber = versionNumber; }

    public BigDecimal getCommissionRate() { return commissionRate; }
    public void setCommissionRate(BigDecimal commissionRate) { this.commissionRate = commissionRate; }

    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }

    public OffsetDateTime getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(OffsetDateTime effectiveFrom) { this.effectiveFrom = effectiveFrom; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
