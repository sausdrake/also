package com.example.autoauction.auth.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * AUTH-TELEGRAM-01: an external identity (provider + stable subject) attached to exactly one AlSo account.
 * The identity is NOT the account: the account (users) keeps its own roles, trust and KYC state, which this
 * object never touches. UNIQUE(provider, provider_subject) in the database is the authoritative guarantee that
 * one external identity belongs to one account.
 */
public class ExternalIdentity {

    private final Long id;
    private final Long userId;
    private final ExternalIdentityProvider provider;
    private final String subject;
    private String displayName;
    private String providerUsername;
    private String phoneNumber;
    private OffsetDateTime phoneVerifiedAt;
    private final BotAccess botAccess;
    private final OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime lastLoginAt;

    public ExternalIdentity(Long id, Long userId, ExternalIdentityProvider provider, String subject, String displayName,
                            String providerUsername, String phoneNumber, OffsetDateTime phoneVerifiedAt, BotAccess botAccess,
                            OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime lastLoginAt) {
        this.id = id;
        this.userId = Objects.requireNonNull(userId, "userId не может быть null");
        this.provider = Objects.requireNonNull(provider, "provider не может быть null");
        this.subject = Objects.requireNonNull(subject, "subject не может быть null");
        this.displayName = displayName;
        this.providerUsername = providerUsername;
        this.phoneNumber = phoneNumber;
        this.phoneVerifiedAt = phoneVerifiedAt;
        this.botAccess = Objects.requireNonNull(botAccess, "botAccess не может быть null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt не может быть null");
        this.updatedAt = updatedAt;
        this.lastLoginAt = lastLoginAt;
    }

    /** A new identity for {@code userId} from a validated token. */
    public static ExternalIdentity attach(Long userId, VerifiedExternalIdentity verified, OffsetDateTime now) {
        ExternalIdentity identity = new ExternalIdentity(null, userId, verified.provider(), verified.subject(), null, null,
                null, null, BotAccess.NOT_REQUESTED, now, now, null);
        identity.refreshProfile(verified, now);
        return identity;
    }

    /**
     * The provider's display data changes freely (name, @username) - it is refreshed, never used to identify.
     * A verified phone is kept only when the token carried one; a token without the phone scope never erases it.
     */
    public void refreshProfile(VerifiedExternalIdentity verified, OffsetDateTime now) {
        if (verified.provider() != provider || !verified.subject().equals(subject)) {
            throw new IllegalArgumentException("Данные другой внешней учётной записи");
        }
        this.displayName = verified.displayName();
        this.providerUsername = verified.providerUsername();
        if (verified.phoneNumber() != null) {
            this.phoneNumber = verified.phoneNumber();
            this.phoneVerifiedAt = now;
        }
        this.updatedAt = now;
    }

    public void recordLogin(OffsetDateTime now) {
        this.lastLoginAt = now;
        this.updatedAt = now;
    }

    public boolean hasVerifiedPhone() {
        return phoneNumber != null && phoneVerifiedAt != null;
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
