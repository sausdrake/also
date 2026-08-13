package com.example.autoauction.common.media.domain;

import java.time.OffsetDateTime;

public class MediaAsset {
    private final Long id;
    private final String storageKey;
    private final Long uploaderId;
    private final OffsetDateTime createdAt;

    public MediaAsset(Long id, String storageKey, Long uploaderId, OffsetDateTime createdAt) {
        this.id = id;
        this.storageKey = storageKey;
        this.uploaderId = uploaderId;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getStorageKey() { return storageKey; }
    public Long getUploaderId() { return uploaderId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
