package com.example.autoauction.identity.web.dto;

/** A fresh, short-lived presigned read URL - never persisted, generated fresh per request. */
public record IdentityDocumentUrlResponse(String url) {
}
