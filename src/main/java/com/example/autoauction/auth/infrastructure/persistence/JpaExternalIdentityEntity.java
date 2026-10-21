package com.example.autoauction.auth.infrastructure.persistence;

import com.example.autoauction.auth.domain.BotAccess;
import com.example.autoauction.auth.domain.ExternalIdentityProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;

/** AUTH-TELEGRAM-01: JPA representation of {@code external_identity} (V63). */
@Entity
@Table(name = "external_identity", uniqueConstraints = {
        @UniqueConstraint(name = "uk_external_identity_provider_subject", columnNames = {"provider", "provider_subject"}),
        @UniqueConstraint(name = "uk_external_identity_user_provider", columnNames = {"user_id", "provider"})
})
public class JpaExternalIdentityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private ExternalIdentityProvider provider;

    @Column(name = "provider_subject", nullable = false, length = 128)
    private String subject;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "provider_username", length = 64)
    private String providerUsername;

    @Column(name = "phone_number", length = 32)
    private String phoneNumber;

    @Column(name = "phone_verified_at")
    private OffsetDateTime phoneVerifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "bot_access", nullable = false, length = 16)
    private BotAccess botAccess;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    protected JpaExternalIdentityEntity() {
    }

    public JpaExternalIdentityEntity(Long id, Long userId, ExternalIdentityProvider provider, String subject, String displayName,
                                     String providerUsername, String phoneNumber, OffsetDateTime phoneVerifiedAt, BotAccess botAccess,
                                     OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime lastLoginAt) {
        this.id = id;
        this.userId = userId;
        this.provider = provider;
        this.subject = subject;
        this.displayName = displayName;
        this.providerUsername = providerUsername;
        this.phoneNumber = phoneNumber;
        this.phoneVerifiedAt = phoneVerifiedAt;
        this.botAccess = botAccess;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastLoginAt = lastLoginAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public ExternalIdentityProvider getProvider() { return provider; }
    public String getSubject() { return subject; }
    public String getDisplayName() { return displayName; }
    public String getProviderUsername() { return providerUsername; }
    public String getPhoneNumber() { return phoneNumber; }
    public OffsetDateTime getPhoneVerifiedAt() { return phoneVerifiedAt; }
    public BotAccess getBotAccess() { return botAccess; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getLastLoginAt() { return lastLoginAt; }
}
