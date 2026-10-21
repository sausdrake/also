package com.example.autoauction.identity.domain;

import java.time.Duration;
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
 *
 * <p>KYC-CAPTURE-01: a slot is issued as a capture of one {@link IdentityCaptureSide} (and, for a
 * document side, of one {@link DocumentType}), may be submitted only in that role, only within its
 * validity window and only once ({@code consumedAt}). Rows created before this change carry no side
 * and can never be submitted again.
 */
public class IdentityDocument {

    private Long id;
    private final String storageKey;
    private final Long uploaderUserId;
    private final String mimeType;
    private final Long sizeBytes;
    private final OffsetDateTime createdAt;
    private final IdentityCaptureSide captureSide;
    private final DocumentType documentType;
    private OffsetDateTime consumedAt;

    public IdentityDocument(String storageKey, Long uploaderUserId, String mimeType, Long sizeBytes, OffsetDateTime createdAt) {
        this(storageKey, uploaderUserId, mimeType, sizeBytes, createdAt, null, null);
    }

    private IdentityDocument(String storageKey, Long uploaderUserId, String mimeType, Long sizeBytes,
                             OffsetDateTime createdAt, IdentityCaptureSide captureSide, DocumentType documentType) {
        this.storageKey = Objects.requireNonNull(storageKey, "storageKey не может быть null");
        this.uploaderUserId = Objects.requireNonNull(uploaderUserId, "uploaderUserId не может быть null");
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt не может быть null");
        this.captureSide = captureSide;
        this.documentType = documentType;
    }

    /**
     * KYC-CAPTURE-01: a new camera-capture slot. A document side must name the document type it belongs to
     * and be one of that type's sides; the selfie belongs to no document type.
     */
    public static IdentityDocument captureSlot(String storageKey, Long uploaderUserId, IdentityCaptureSide side,
                                               DocumentType documentType, String mimeType, OffsetDateTime createdAt) {
        Objects.requireNonNull(side, "Сторона снимка обязательна");
        if (side == IdentityCaptureSide.SELFIE) {
            if (documentType != null) {
                throw new IllegalArgumentException("Селфи не относится к типу документа");
            }
        } else {
            if (documentType == null) {
                throw new IllegalArgumentException("Для снимка документа нужен тип документа");
            }
            if (!DocumentCapturePolicy.allows(documentType, side)) {
                throw new IllegalArgumentException(String.format(
                        "Для документа %s сторона %s не снимается", documentType, side));
            }
        }
        return new IdentityDocument(storageKey, uploaderUserId, mimeType, null, createdAt, side, documentType);
    }

    /** Reconstruction constructor (legacy shape), kept for rows/tests without capture metadata. */
    public IdentityDocument(Long id, String storageKey, Long uploaderUserId, String mimeType, Long sizeBytes, OffsetDateTime createdAt) {
        this(id, storageKey, uploaderUserId, mimeType, sizeBytes, createdAt, null, null, null);
    }

    /** Reconstruction constructor, used by the persistence mapper. */
    public IdentityDocument(Long id, String storageKey, Long uploaderUserId, String mimeType, Long sizeBytes,
                            OffsetDateTime createdAt, IdentityCaptureSide captureSide, DocumentType documentType,
                            OffsetDateTime consumedAt) {
        this(storageKey, uploaderUserId, mimeType, sizeBytes, createdAt, captureSide, documentType);
        this.id = id;
        this.consumedAt = consumedAt;
    }

    public boolean isOwnedBy(Long userId) {
        return Objects.equals(this.uploaderUserId, userId);
    }

    /** KYC-CAPTURE-01: a slot is valid for submission only within {@code validity} of its creation. */
    public boolean isExpiredAt(OffsetDateTime now, Duration validity) {
        return now.isAfter(createdAt.plus(validity));
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public Long getId() { return id; }
    public String getStorageKey() { return storageKey; }
    public Long getUploaderUserId() { return uploaderUserId; }
    public String getMimeType() { return mimeType; }
    public Long getSizeBytes() { return sizeBytes; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public IdentityCaptureSide getCaptureSide() { return captureSide; }
    public DocumentType getDocumentType() { return documentType; }
    public OffsetDateTime getConsumedAt() { return consumedAt; }
}
