package com.example.autoauction.diagnostic.media.web.dto;

import com.example.autoauction.diagnostic.media.domain.DiagnosticMedia;
import com.example.autoauction.diagnostic.media.domain.MediaType;
import com.example.autoauction.diagnostic.media.domain.ProcessingStatus;
import com.example.autoauction.diagnostic.media.domain.UploadStatus;

/** No storageKey, no URL - callers request a read URL separately, per-view. */
public record MediaResponse(
        Long id,
        MediaType mediaType,
        String mimeType,
        Long sizeBytes,
        Integer sortOrder,
        String caption,
        UploadStatus uploadStatus,
        ProcessingStatus processingStatus,
        Integer durationSeconds,
        Integer width,
        Integer height,
        boolean thumbnailAvailable) {

    public static MediaResponse fromDomain(DiagnosticMedia media) {
        return new MediaResponse(
                media.getId(),
                media.getMediaType(),
                media.getMimeType(),
                media.getSizeBytes(),
                media.getSortOrder(),
                media.getCaption(),
                media.getUploadStatus(),
                media.getProcessingStatus(),
                media.getDurationSeconds(),
                media.getWidth(),
                media.getHeight(),
                media.getThumbnailStorageKey() != null);
    }
}
