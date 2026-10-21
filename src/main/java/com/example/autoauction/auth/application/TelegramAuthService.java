package com.example.autoauction.auth.application;

import com.example.autoauction.auth.domain.ExternalIdentityProvider;
import com.example.autoauction.auth.domain.OidcAuthorizationRequest;
import com.example.autoauction.auth.domain.OidcPurpose;
import com.example.autoauction.auth.domain.VerifiedExternalIdentity;
import com.example.autoauction.auth.domain.port.ExternalIdentityRepository;
import com.example.autoauction.auth.telegram.TelegramIdTokenValidator;
import com.example.autoauction.auth.telegram.TelegramOidcProperties;
import com.example.autoauction.auth.telegram.TelegramTokenClient;
import com.example.autoauction.auth.telegram.TelegramUserChannel;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AUTH-TELEGRAM-01: sign-in and account linking with Telegram (OIDC Authorization Code + PKCE). Telegram is the
 * way to OBTAIN AlSo authentication - the result is the existing AlSo JWT / refresh token of the resolved
 * account (AuthService), with that account's own roles. Telegram never grants roles, trust or KYC.
 *
 * <p>Sign-in with Telegram is offered to plain user accounts only (exactly ROLE_USER): administrator and
 * diagnostician accounts keep password login until the product decides otherwise (P2/P3), and cannot be linked.
 */
@Slf4j
@Service
public class TelegramAuthService {

    static final String ROLE_USER = "ROLE_USER";

    private final TelegramAuthorizationService authorizations;
    private final TelegramTokenClient tokenClient;
    private final TelegramIdTokenValidator validator;
    private final ExternalIdentityResolver resolver;
    private final ExternalIdentityRepository identities;
    private final UserRepository users;
    private final AuthService authService;
    private final TelegramOidcProperties properties;
    private final TelegramUserChannel userChannel;

    public TelegramAuthService(TelegramAuthorizationService authorizations, TelegramTokenClient tokenClient,
                               TelegramIdTokenValidator validator, ExternalIdentityResolver resolver,
                               ExternalIdentityRepository identities, UserRepository users, AuthService authService,
                               TelegramOidcProperties properties, TelegramUserChannel userChannel) {
        this.authorizations = authorizations;
        this.tokenClient = tokenClient;
        this.validator = validator;
        this.resolver = resolver;
        this.identities = identities;
        this.users = users;
        this.authService = authService;
        this.properties = properties;
        this.userChannel = userChannel;
    }

    public boolean isEnabled() {
        return properties.isConfigured();
    }

    public TelegramAuthorizationService.Started startLogin(String binding, boolean includePhone) {
        return authorizations.start(OidcPurpose.LOGIN, null, binding, includePhone);
    }

    public TelegramAuthorizationService.Started startLink(Long userId, String binding) {
        requirePlainUser(loadUser(userId));
        return authorizations.start(OidcPurpose.LINK, userId, binding, false);
    }

    /** Login callback: the account of this Telegram identity (created on first login) gets AlSo tokens. */
    public AuthService.AuthResponse completeLogin(String code, String state, String binding) {
        VerifiedExternalIdentity verified = verify(authorizations.consume(state, binding, OidcPurpose.LOGIN), code);
        Long userId;
        try {
            userId = resolver.loginOrCreate(verified);
        } catch (DataIntegrityViolationException raceLost) {
            userId = resolver.existingUserId(verified);
        }
        User user = loadUser(userId);
        requirePlainUser(user);
        return authService.issueTokens(user);
    }

    /** Link callback: only the authenticated account that started the round trip can finish it. */
    public void completeLink(Long userId, String code, String state, String binding) {
        OidcAuthorizationRequest request = authorizations.consume(state, binding, OidcPurpose.LINK);
        if (!Objects.equals(request.getLinkUserId(), userId)) {
            throw new TelegramAuthException(TelegramAuthException.Reason.INVALID_STATE);
        }
        requirePlainUser(loadUser(userId));
        resolver.attach(userId, verify(request, code));
    }

    /** What the owner may see about their own Telegram link - never the Telegram id or the phone number. */
    public record LinkStatus(boolean enabled, boolean linked, String displayName, boolean phoneVerified, boolean botCanMessage) {}

    public LinkStatus status(Long userId) {
        return identities.findByUserIdAndProvider(userId, ExternalIdentityProvider.TELEGRAM)
                .map(i -> new LinkStatus(isEnabled(), true, i.getDisplayName(), i.hasVerifiedPhone(), userChannel.canMessage(userId)))
                .orElseGet(() -> new LinkStatus(isEnabled(), false, null, false, false));
    }

    private VerifiedExternalIdentity verify(OidcAuthorizationRequest request, String code) {
        if (code == null || code.isBlank() || code.length() > 512) {
            throw new TelegramAuthException(TelegramAuthException.Reason.INVALID_CODE);
        }
        String idToken = tokenClient.exchangeCode(code, request.getCodeVerifier());
        return validator.validate(idToken, request.getNonce());
    }

    private User loadUser(Long userId) {
        return users.findById(userId).orElseThrow(() -> new TelegramAuthException(TelegramAuthException.Reason.INVALID_STATE));
    }

    private static void requirePlainUser(User user) {
        Set<String> roleNames = user.getRoles().stream().map(Role::getName).collect(Collectors.toSet());
        if (!roleNames.equals(Set.of(ROLE_USER))) {
            throw new TelegramAuthException(TelegramAuthException.Reason.LOGIN_NOT_ALLOWED);
        }
    }
}
