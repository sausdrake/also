package com.example.autoauction.diagnostic.media.web.dto;

import com.example.autoauction.diagnostic.media.domain.MediaType;
import com.example.autoauction.diagnostic.media.domain.UploadStatus;

import java.time.Instant;

/** No storage key, no reusable public URL - uploadUrl is single-use/short-lived. */
public record MediaIntentResponse(
        Long mediaId,
        String uploadUrl,
        Instant uploadUrlExpiresAt,
        MediaType mediaType,
        UploadStatus uploadStatus) {
}
