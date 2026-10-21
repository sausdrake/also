package com.example.autoauction.auth.domain;

import java.util.Objects;

/**
 * AUTH-TELEGRAM-01: an identity whose token passed server-side cryptographic validation. Only {@code subject}
 * identifies the person; the rest is display metadata that may change at any time. {@code phoneNumber} is set
 * only when the provider marked it verified.
 */
public record VerifiedExternalIdentity(
        ExternalIdentityProvider provider,
        String subject,
        String displayName,
        String providerUsername,
        String phoneNumber
) {
    public VerifiedExternalIdentity {
        Objects.requireNonNull(provider, "provider не может быть null");
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject обязателен");
        }
    }

    /** Never prints the subject or the phone (logs). */
    @Override
    public String toString() {
        return "VerifiedExternalIdentity[" + provider + ", redacted]";
    }
}
