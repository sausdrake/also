package com.example.autoauction.identity.infrastructure.persistence;

import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityCaptureSide;
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

@Entity
@Table(name = "identity_document",
        uniqueConstraints = @UniqueConstraint(name = "uk_identity_document_storage_key", columnNames = "storage_key"))
public class JpaIdentityDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storage_key", nullable = false, unique = true, length = 500)
    private String storageKey;

    @Column(name = "uploader_user_id", nullable = false)
    private Long uploaderUserId;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    // KYC-CAPTURE-01 (V62): what the capture slot was issued for, and when it was submitted (single use)
    @Enumerated(EnumType.STRING)
    @Column(name = "capture_side", length = 16)
    private IdentityCaptureSide captureSide;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", length = 24)
    private DocumentType documentType;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    protected JpaIdentityDocumentEntity() {
    }

    public JpaIdentityDocumentEntity(Long id, String storageKey, Long uploaderUserId, String mimeType,
                                      Long sizeBytes, OffsetDateTime createdAt) {
        this.id = id;
        this.storageKey = storageKey;
        this.uploaderUserId = uploaderUserId;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.createdAt = createdAt;
    }

    public JpaIdentityDocumentEntity(Long id, String storageKey, Long uploaderUserId, String mimeType,
                                      Long sizeBytes, OffsetDateTime createdAt, IdentityCaptureSide captureSide,
                                      DocumentType documentType, OffsetDateTime consumedAt) {
        this(id, storageKey, uploaderUserId, mimeType, sizeBytes, createdAt);
        this.captureSide = captureSide;
        this.documentType = documentType;
        this.consumedAt = consumedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public Long getUploaderUserId() { return uploaderUserId; }
    public void setUploaderUserId(Long uploaderUserId) { this.uploaderUserId = uploaderUserId; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public IdentityCaptureSide getCaptureSide() { return captureSide; }
    public void setCaptureSide(IdentityCaptureSide captureSide) { this.captureSide = captureSide; }
    public DocumentType getDocumentType() { return documentType; }
    public void setDocumentType(DocumentType documentType) { this.documentType = documentType; }
    public OffsetDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(OffsetDateTime consumedAt) { this.consumedAt = consumedAt; }
}
