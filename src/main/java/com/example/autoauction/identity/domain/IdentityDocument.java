package com.example.autoauction.identity.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Phase 3.1 §4/Phase 4 §6: the ownership ledger for an uploaded identity document's bytes in
 * object storage. Its own existence for a given {@code storageKey} IS the proof that key was
 * issued through the identity-document upload flow (intent-binding by construction) - a key
 * never issued this way has no corresponding row and fails the submission-time ownership lookup.
 * A separate table from {@code diagnostic.media}'s own metadata table by design - this module
 * reuses the {@code StorageClient} PORT/adapter (infrastructure capability), never another
 * module's own domain data (Phase 3.1 §4, Phase 3 ADR-08).
 */
public class IdentityDocument {

    private Long id;
    private final String storageKey;
    private final Long uploaderUserId;
    private final String mimeType;
    private final Long sizeBytes;
    private final OffsetDateTime createdAt;

    public IdentityDocument(String storageKey, Long uploaderUserId, String mimeType, Long sizeBytes, OffsetDateTime createdAt) {
        this.storageKey = Objects.requireNonNull(storageKey, "storageKey не может быть null");
        this.uploaderUserId = Objects.requireNonNull(uploaderUserId, "uploaderUserId не может быть null");
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt не может быть null");
    }

    /** Reconstruction constructor, used exclusively by the persistence mapper. */
    public IdentityDocument(Long id, String storageKey, Long uploaderUserId, String mimeType, Long sizeBytes, OffsetDateTime createdAt) {
        this(storageKey, uploaderUserId, mimeType, sizeBytes, createdAt);
        this.id = id;
    }

    public boolean isOwnedBy(Long userId) {
        return Objects.equals(this.uploaderUserId, userId);
    }

    public Long getId() { return id; }
    public String getStorageKey() { return storageKey; }
    public Long getUploaderUserId() { return uploaderUserId; }
    public String getMimeType() { return mimeType; }
    public Long getSizeBytes() { return sizeBytes; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
