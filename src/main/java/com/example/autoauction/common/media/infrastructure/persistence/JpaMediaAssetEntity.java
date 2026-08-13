package com.example.autoauction.common.media.infrastructure.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "media_assets")
public class JpaMediaAssetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storage_key", nullable = false, unique = true, length = 255)
    private String storageKey;

    @Column(name = "uploader_id", nullable = false)
    private Long uploaderId;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    protected JpaMediaAssetEntity() {}

    public JpaMediaAssetEntity(String storageKey, Long uploaderId) {
        this.storageKey = storageKey;
        this.uploaderId = uploaderId;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }

    public Long getUploaderId() { return uploaderId; }
    public void setUploaderId(Long uploaderId) { this.uploaderId = uploaderId; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
