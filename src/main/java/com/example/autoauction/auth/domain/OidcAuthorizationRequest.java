package com.example.autoauction.auth.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * AUTH-TELEGRAM-01: one OIDC authorization round trip, kept server-side. The browser only ever sees the
 * {@code state} (stored here hashed) and keeps a random binding secret of its own (stored here hashed): a
 * callback must present both, so a code + state lured into another browser is useless (login CSRF). The PKCE
 * verifier and the nonce never leave the server. Single use ({@code consumedAt}), short-lived.
 */
public class OidcAuthorizationRequest {

    private final Long id;
    private final String stateHash;
    private final String bindingHash;
    private final String codeVerifier;
    private final String nonce;
    private final OidcPurpose purpose;
    private final Long linkUserId;
    private final String scope;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime expiresAt;
    private final OffsetDateTime consumedAt;

    public OidcAuthorizationRequest(Long id, String stateHash, String bindingHash, String codeVerifier, String nonce,
                                    OidcPurpose purpose, Long linkUserId, String scope, OffsetDateTime createdAt,
                                    OffsetDateTime expiresAt, OffsetDateTime consumedAt) {
        this.id = id;
        this.stateHash = Objects.requireNonNull(stateHash, "stateHash не может быть null");
        this.bindingHash = Objects.requireNonNull(bindingHash, "bindingHash не может быть null");
        this.codeVerifier = Objects.requireNonNull(codeVerifier, "codeVerifier не может быть null");
        this.nonce = Objects.requireNonNull(nonce, "nonce не может быть null");
        this.purpose = Objects.requireNonNull(purpose, "purpose не может быть null");
        if ((purpose == OidcPurpose.LINK) != (linkUserId != null)) {
            throw new IllegalArgumentException("linkUserId задаётся только для привязки");
        }
        this.linkUserId = linkUserId;
        this.scope = Objects.requireNonNull(scope, "scope не может быть null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt не может быть null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt не может быть null");
        this.consumedAt = consumedAt;
    }

    public boolean isExpiredAt(OffsetDateTime now) {
        return now.isAfter(expiresAt);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public Long getId() { return id; }
    public String getStateHash() { return stateHash; }
    public String getBindingHash() { return bindingHash; }
    public String getCodeVerifier() { return codeVerifier; }
    public String getNonce() { return nonce; }
    public OidcPurpose getPurpose() { return purpose; }
    public Long getLinkUserId() { return linkUserId; }
    public String getScope() { return scope; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getConsumedAt() { return consumedAt; }
}
