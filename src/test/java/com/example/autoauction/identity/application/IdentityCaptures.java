package com.example.autoauction.identity.application;

import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityCaptureSide;
import com.example.autoauction.identity.domain.IdentityDocument;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** KYC-CAPTURE-01 test fixtures: camera-capture slots as the repository returns them. */
final class IdentityCaptures {

    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    static final OffsetDateTime NOW = OffsetDateTime.ofInstant(T0, ZoneOffset.UTC);

    private IdentityCaptures() {
    }

    /** An unconsumed capture slot issued at {@link #NOW}. */
    static IdentityDocument capture(Long id, String key, Long owner, IdentityCaptureSide side, DocumentType type,
                                    String mimeType) {
        return new IdentityDocument(id, key, owner, mimeType, 3L, NOW, side, type, null);
    }

    static IdentityDocument front(Long id, String key, Long owner) {
        return capture(id, key, owner, IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, "image/jpeg");
    }

    static IdentityDocument back(Long id, String key, Long owner) {
        return capture(id, key, owner, IdentityCaptureSide.BACK, DocumentType.NATIONAL_ID, "image/jpeg");
    }

    static IdentityDocument selfie(Long id, String key, Long owner) {
        return capture(id, key, owner, IdentityCaptureSide.SELFIE, null, "image/jpeg");
    }
}
