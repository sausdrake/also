package com.example.autoauction.diagnostic.media.domain;

/**
 * MEDIA-2/MEDIA-3.2: the upload lifecycle, deliberately separate from
 * ProcessingStatus. READY here means only: the object exists in storage and
 * passed basic (existence/size/magic-byte) validation at finalize time. It
 * does NOT mean the media has been fully processed (codec probed, duration
 * extracted, thumbnail generated) - see ProcessingStatus for that.
 */
public enum UploadStatus {
    PENDING_UPLOAD,
    READY,
    FAILED,
    EXPIRED,
    DELETED
}
