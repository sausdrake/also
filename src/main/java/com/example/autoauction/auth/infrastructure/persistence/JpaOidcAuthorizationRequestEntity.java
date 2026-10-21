package com.example.autoauction.auth.infrastructure.persistence;

import com.example.autoauction.auth.domain.OidcPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** AUTH-TELEGRAM-01: JPA representation of {@code oidc_authorization_request} (V63). */
@Entity
@Table(name = "oidc_authorization_request")
public class JpaOidcAuthorizationRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "state_hash", nullable = false, unique = true, length = 64)
    private String stateHash;

    @Column(name = "binding_hash", nullable = false, length = 64)
    private String bindingHash;

    @Column(name = "code_verifier", nullable = false, length = 128)
    private String codeVerifier;

    @Column(name = "nonce", nullable = false, length = 64)
    private String nonce;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 8)
    private OidcPurpose purpose;

    @Column(name = "link_user_id")
    private Long linkUserId;

    @Column(name = "scope", nullable = false, length = 128)
    private String scope;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    protected JpaOidcAuthorizationRequestEntity() {
    }

    public JpaOidcAuthorizationRequestEntity(Long id, String stateHash, String bindingHash, String codeVerifier, String nonce,
                                             OidcPurpose purpose, Long linkUserId, String scope, OffsetDateTime createdAt,
                                             OffsetDateTime expiresAt, OffsetDateTime consumedAt) {
        this.id = id;
        this.stateHash = stateHash;
        this.bindingHash = bindingHash;
        this.codeVerifier = codeVerifier;
        this.nonce = nonce;
        this.purpose = purpose;
        this.linkUserId = linkUserId;
        this.scope = scope;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.consumedAt = consumedAt;
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
