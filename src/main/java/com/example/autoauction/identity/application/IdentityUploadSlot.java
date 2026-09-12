package com.example.autoauction.identity.application;

import java.net.URI;
import java.time.Instant;

/** Result of requesting a fresh upload slot - the server-generated key plus a presigned PUT URL. */
public record IdentityUploadSlot(String storageKey, URI uploadUrl, Instant expiresAt) {
}
