package com.example.autoauction.identity.infrastructure.persistence;

import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityVerificationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;

@Entity
@Table(name = "identity_verification",
        uniqueConstraints = @UniqueConstraint(name = "uk_identity_verification_user_id", columnNames = "user_id"))
public class JpaIdentityVerificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private IdentityVerificationStatus status;

    @Column(name = "issuing_country", nullable = false, length = 8)
    private String issuingCountry;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 24)
    private DocumentType documentType;

    @Column(name = "canonical_document_number", nullable = false, length = 64)
    private String canonicalDocumentNumber;

    @Column(name = "current_document_id")
    private Long currentDocumentId;

    @Column(name = "current_selfie_document_id")
    private Long currentSelfieDocumentId;

    @Column(name = "current_document_back_id")   // KYC-CAPTURE-01 / V62
    private Long currentDocumentBackId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    protected JpaIdentityVerificationEntity() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public IdentityVerificationStatus getStatus() { return status; }
    public void setStatus(IdentityVerificationStatus status) { this.status = status; }
    public String getIssuingCountry() { return issuingCountry; }
    public void setIssuingCountry(String issuingCountry) { this.issuingCountry = issuingCountry; }
    public DocumentType getDocumentType() { return documentType; }
    public void setDocumentType(DocumentType documentType) { this.documentType = documentType; }
    public String getCanonicalDocumentNumber() { return canonicalDocumentNumber; }
    public void setCanonicalDocumentNumber(String canonicalDocumentNumber) { this.canonicalDocumentNumber = canonicalDocumentNumber; }
    public Long getCurrentDocumentId() { return currentDocumentId; }
    public void setCurrentDocumentId(Long currentDocumentId) { this.currentDocumentId = currentDocumentId; }
    public Long getCurrentSelfieDocumentId() { return currentSelfieDocumentId; }
    public void setCurrentSelfieDocumentId(Long currentSelfieDocumentId) { this.currentSelfieDocumentId = currentSelfieDocumentId; }
    public Long getCurrentDocumentBackId() { return currentDocumentBackId; }
    public void setCurrentDocumentBackId(Long currentDocumentBackId) { this.currentDocumentBackId = currentDocumentBackId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
