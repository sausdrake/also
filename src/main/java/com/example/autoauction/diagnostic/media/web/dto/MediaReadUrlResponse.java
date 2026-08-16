package com.example.autoauction.diagnostic.media.web.dto;

import java.time.Instant;

/** Never persisted - generated fresh on every request. */
public record MediaReadUrlResponse(String url, Instant expiresAt) {
}
