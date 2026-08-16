package com.example.autoauction.diagnostic.media.domain.port;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Provider-neutral object storage port. The domain/application layer knows
 * nothing about R2, S3, or the AWS SDK - only this interface and its value
 * types. MEDIA-3.2 implements a single-object-presigned-upload adapter
 * (R2StorageClient) against this port; a future multipart-capable
 * implementation (initiate/uploadPart/complete/abort) can be added later
 * purely as new methods on this same port and a new/extended adapter,
 * WITHOUT any DiagnosticMedia schema change - storageKey already identifies
 * the object regardless of how many parts it took to upload.
 */
public interface StorageClient {

    /** Presigned single-PUT upload URL for a server-chosen storage key. */
    PresignedUpload createPresignedUpload(String storageKey, String contentType, Duration ttl);

    /** HEAD equivalent - empty if the object does not exist. */
    Optional<StoredObjectMetadata> headObject(String storageKey);

    /** First N bytes of the object, for magic-byte validation. Empty if the object does not exist. */
    Optional<byte[]> fetchLeadingBytes(String storageKey, int byteCount);

    /** Short-lived presigned GET URL. Never persisted - generated fresh per request. */
    URI createPresignedReadUrl(String storageKey, Duration ttl);

    /** Best-effort delete. Implementations must not throw for an already-absent object. */
    void deleteObject(String storageKey);

    record PresignedUpload(URI uploadUrl, Instant expiresAt) {}

    record StoredObjectMetadata(long sizeBytes, String contentType) {}
}
