package com.example.autoauction.policy.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

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

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Policy Administration Decision 2: established only by a successful publication claim. */
    @Column(name = "published_by")
    private Long publishedBy;

    /** Policy Administration Decision 2: established only by a successful publication claim. */
    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    /**
     * Policy Administration Decision 5: immutable draft lineage, captured once at creation,
     * never changed afterwards (updatable = false).
     */
    @Column(name = "based_on_published_version_id", updatable = false)
    private Long basedOnPublishedVersionId;

    /** Boundary A: entity-level optimistic lock, protects this row's own fields. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    /**
     * Boundary C: aggregate-configuration freshness counter (Decision 6) - deliberately a
     * PLAIN column, not {@code @Version} (it is incremented by a dedicated atomic SQL statement,
     * never via Hibernate's own entity-save dirty-checking - see
     * {@code PolicyVersionRepositoryAdapter.incrementConfigurationRevision}).
     */
    @Column(name = "configuration_revision", nullable = false)
    private Long configurationRevision = 0L;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getVersionNumber() { return versionNumber; }
    public void setVersionNumber(Long versionNumber) { this.versionNumber = versionNumber; }

    public BigDecimal getCommissionRate() { return commissionRate; }
    public void setCommissionRate(BigDecimal commissionRate) { this.commissionRate = commissionRate; }

    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public Long getPublishedBy() { return publishedBy; }
    public void setPublishedBy(Long publishedBy) { this.publishedBy = publishedBy; }

    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(OffsetDateTime publishedAt) { this.publishedAt = publishedAt; }

    public Long getBasedOnPublishedVersionId() { return basedOnPublishedVersionId; }
    public void setBasedOnPublishedVersionId(Long basedOnPublishedVersionId) { this.basedOnPublishedVersionId = basedOnPublishedVersionId; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public Long getConfigurationRevision() { return configurationRevision; }
    public void setConfigurationRevision(Long configurationRevision) { this.configurationRevision = configurationRevision; }
}
