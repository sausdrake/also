package com.example.autoauction.auth.application;

import com.example.autoauction.auth.domain.ExternalIdentity;
import com.example.autoauction.auth.domain.VerifiedExternalIdentity;
import com.example.autoauction.auth.domain.port.ExternalIdentityRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * AUTH-TELEGRAM-01: validated external identity -> AlSo account. A known identity returns its own account
 * (display data refreshed, nothing else). An unknown identity gets a NEW account: ROLE_USER only, generated
 * username, no email, no phone, no usable password, untrusted, no KYC - exactly what password registration gives,
 * minus the credentials. Never matched to an existing account by name, username, email or phone.
 *
 * <p>Concurrency: two first logins of the same identity race on UNIQUE(provider, provider_subject); the loser's
 * transaction rolls back completely (its user, deposit and identity) and it resolves the winner's account.
 */
@Slf4j
@Service
public class ExternalIdentityResolver {

    /** Marker stored as password_hash of an account created without a password - never a BCrypt hash. */
    public static final String NO_PASSWORD = "!no-password:external-identity";
    private static final String USERNAME_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ExternalIdentityRepository identities;
    private final UserRepository users;
    private final RoleRepository roles;
    private final DepositService depositService;
    private final Clock clock;

    public ExternalIdentityResolver(ExternalIdentityRepository identities, UserRepository users, RoleRepository roles,
                                    DepositService depositService, Clock clock) {
        this.identities = identities;
        this.users = users;
        this.roles = roles;
        this.depositService = depositService;
        this.clock = clock;
    }

    /** Marks a login of a known identity, or creates the account; returns the account's user id. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long loginOrCreate(VerifiedExternalIdentity verified) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        ExternalIdentity existing = identities.findByProviderAndSubject(verified.provider(), verified.subject()).orElse(null);
        if (existing != null) {
            existing.refreshProfile(verified, now);
            existing.recordLogin(now);
            identities.save(existing);
            return existing.getUserId();
        }
        User user = createAccount();
        ExternalIdentity identity = ExternalIdentity.attach(user.getId(), verified, now);
        identity.recordLogin(now);
        identities.save(identity);          // UNIQUE(provider, subject): a concurrent first login fails here
        depositService.createDeposit(user.getId(), BigDecimal.ZERO);
        log.info("New account {} created from an external identity", user.getId());
        return user.getId();
    }

    /** After a lost race: the identity now exists (committed by the winner). */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Long existingUserId(VerifiedExternalIdentity verified) {
        return identities.findByProviderAndSubject(verified.provider(), verified.subject())
                .map(ExternalIdentity::getUserId)
                .orElseThrow(() -> new TelegramAuthException(TelegramAuthException.Reason.IDENTITY_CONFLICT));
    }

    /**
     * Attaches the identity to {@code userId} (account linking). Idempotent for the same account; refused when the
     * identity belongs to another account or the account already has another identity of this provider.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attach(Long userId, VerifiedExternalIdentity verified) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        ExternalIdentity owner = identities.findByProviderAndSubject(verified.provider(), verified.subject()).orElse(null);
        if (owner != null) {
            if (!owner.getUserId().equals(userId)) {
                throw new TelegramAuthException(TelegramAuthException.Reason.IDENTITY_CONFLICT);
            }
            owner.refreshProfile(verified, now);
            identities.save(owner);
            return;
        }
        if (identities.findByUserIdAndProvider(userId, verified.provider()).isPresent()) {
            throw new TelegramAuthException(TelegramAuthException.Reason.IDENTITY_CONFLICT);
        }
        try {
            identities.save(ExternalIdentity.attach(userId, verified, now));
        } catch (DataIntegrityViolationException e) {
            throw new TelegramAuthException(TelegramAuthException.Reason.IDENTITY_CONFLICT);
        }
    }

    private User createAccount() {
        Role userRole = roles.findByName("ROLE_USER")
                .orElseThrow(() -> new IllegalStateException("Default role ROLE_USER not found"));
        for (int attempt = 0; attempt < 5; attempt++) {
            String username = generatedUsername();
            if (users.findByUsername(username).isEmpty()) {
                return users.save(new User(null, username, null, NO_PASSWORD, true, Set.of(userRole)));
            }
        }
        throw new IllegalStateException("Could not generate a free username");
    }

    /** Never derived from the provider's name or @username (those change and are not ours to publish). */
    static String generatedUsername() {
        StringBuilder sb = new StringBuilder("user_");
        for (int i = 0; i < 10; i++) {
            sb.append(USERNAME_ALPHABET.charAt(RANDOM.nextInt(USERNAME_ALPHABET.length())));
        }
        return sb.toString();
    }
}
