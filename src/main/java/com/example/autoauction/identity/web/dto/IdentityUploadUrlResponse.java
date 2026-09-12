package com.example.autoauction.identity.web.dto;

import com.example.autoauction.identity.application.IdentityUploadSlot;

import java.time.Instant;

public record IdentityUploadUrlResponse(String storageKey, String uploadUrl, Instant expiresAt) {
    public static IdentityUploadUrlResponse fromSlot(IdentityUploadSlot slot) {
        return new IdentityUploadUrlResponse(slot.storageKey(), slot.uploadUrl().toString(), slot.expiresAt());
    }
}
