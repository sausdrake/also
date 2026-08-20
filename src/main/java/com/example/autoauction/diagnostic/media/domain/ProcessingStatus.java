package com.example.autoauction.diagnostic.media.domain;

/**
 * MEDIA-3.2 introduced this enum without a real video-processing pipeline;
 * MEDIA-3.4 implements it (FFmpeg subprocess). PHOTO items are NOT_REQUIRED.
 * VIDEO items become PENDING once the upload itself is READY, then follow:
 * PENDING -[atomic claim]-> PROCESSING -[success]-> READY, or
 * PROCESSING -[failure]-> FAILED (terminal for automatic processing - see
 * DiagnosticMediaRepository.claimForProcessing/retryFailedProcessing).
 * durationSeconds/width/height/thumbnailStorageKey must remain NULL until
 * processing actually runs - never populated from client claims.
 */
public enum ProcessingStatus {
    NOT_REQUIRED,
    PENDING,
    PROCESSING,
    READY,
    FAILED
}
