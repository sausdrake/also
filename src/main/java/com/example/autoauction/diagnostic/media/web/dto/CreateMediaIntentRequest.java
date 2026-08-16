package com.example.autoauction.diagnostic.media.web.dto;

import com.example.autoauction.diagnostic.media.domain.MediaType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * MEDIA-3.2: only client-supplied metadata NEEDED to request an upload.
 * storageKey/uploaderId/diagnosticRequestId are never accepted from the
 * client - the server derives them entirely (path + authenticated principal
 * + DB state). durationSeconds/width/height are intentionally NOT accepted
 * here: any client-reported media metadata is a UI hint at most, never
 * authoritative, and MEDIA-3.2 does not persist unverified values at all.
 */
@Data
public class CreateMediaIntentRequest {

    @NotNull(message = "mediaType обязателен")
    private MediaType mediaType;

    @NotNull(message = "mimeType обязателен")
    private String mimeType;

    @NotNull(message = "sizeBytes обязателен")
    @Positive(message = "sizeBytes должен быть положительным")
    private Long sizeBytes;
}
