package com.example.autoauction.diagnostic.media.domain;

/**
 * MEDIA-3.2 does not implement a real video-processing pipeline. PHOTO items
 * are NOT_REQUIRED; VIDEO items become PENDING once the upload itself is
 * READY and stay PENDING indefinitely until a future phase adds real
 * server-side processing (thumbnail generation, duration extraction, codec
 * probing). durationSeconds/width/height/thumbnailStorageKey must remain
 * NULL until that processing actually runs - never populated from client
 * claims.
 */
public enum ProcessingStatus {
    NOT_REQUIRED,
    PENDING,
    READY,
    FAILED
}
