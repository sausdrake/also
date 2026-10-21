package com.example.autoauction.auth.application;

import com.example.autoauction.auth.domain.OidcAuthorizationRequest;
import com.example.autoauction.auth.domain.OidcPurpose;
import com.example.autoauction.auth.domain.port.OidcAuthorizationRequestRepository;
import com.example.autoauction.auth.telegram.TelegramOidcProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * AUTH-TELEGRAM-01: the server side of an OIDC round trip - creates the Telegram authorization URL (state, PKCE
 * S256, nonce) and later accepts a callback exactly once. A callback must present the state AND the browser's
 * binding secret it was started with; it must be the same purpose (a login state cannot finish a link and vice
 * versa), unexpired and unused. The request is consumed BEFORE the code is exchanged, so a replayed callback never
 * reaches Telegram twice.
 */
@Service
public class TelegramAuthorizationService {

    static final Duration REQUEST_TTL = Duration.ofMinutes(10);
    static final String SCOPE_LOGIN = "openid profile";
    static final String SCOPE_PHONE = "phone";
    private static final int MIN_BINDING_LENGTH = 32;
    private static final int MAX_BINDING_LENGTH = 128;

    private final OidcAuthorizationRequestRepository requests;
    private final TelegramOidcProperties properties;
    private final Clock clock;

    public TelegramAuthorizationService(OidcAuthorizationRequestRepository requests, TelegramOidcProperties properties,
                                        Clock clock) {
        this.requests = requests;
        this.properties = properties;
        this.clock = clock;
    }

    /** Started round trip: the URL to send the browser to, and the state the browser will get back. */
    public record Started(String authorizationUrl, String state) {}

    @Transactional
    public Started start(OidcPurpose purpose, Long linkUserId, String binding, boolean includePhone) {
        requireConfigured();
        requireBinding(binding);
        OffsetDateTime now = OffsetDateTime.now(clock);
        requests.deleteExpiredBefore(now.minus(REQUEST_TTL));
        String state = OidcSecrets.random();
        String codeVerifier = OidcSecrets.random();
        String nonce = OidcSecrets.random();
        String scope = includePhone ? SCOPE_LOGIN + " " + SCOPE_PHONE : SCOPE_LOGIN;
        requests.save(new OidcAuthorizationRequest(null, OidcSecrets.sha256Hex(state), OidcSecrets.sha256Hex(binding),
                codeVerifier, nonce, purpose, linkUserId, scope, now, now.plus(REQUEST_TTL), null));

        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", properties.clientId());
        query.put("redirect_uri", properties.redirectUri());
        query.put("response_type", "code");
        query.put("scope", scope);
        query.put("state", state);
        query.put("nonce", nonce);
        query.put("code_challenge", OidcSecrets.pkceChallenge(codeVerifier));
        query.put("code_challenge_method", "S256");
        String url = properties.authorizationEndpoint() + "?" + query.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return new Started(url, state);
    }

    /**
     * Accepts the callback once. Any mismatch is the same INVALID_STATE answer - the caller learns nothing about
     * which check failed.
     */
    @Transactional
    public OidcAuthorizationRequest consume(String state, String binding, OidcPurpose purpose) {
        requireConfigured();
        if (state == null || state.isBlank() || state.length() > 256 || binding == null || binding.length() > MAX_BINDING_LENGTH) {
            throw new TelegramAuthException(TelegramAuthException.Reason.INVALID_STATE);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        OidcAuthorizationRequest request = requests.findByStateHash(OidcSecrets.sha256Hex(state))
                .orElseThrow(() -> new TelegramAuthException(TelegramAuthException.Reason.INVALID_STATE));
        boolean bindingMatches = MessageDigest.isEqual(
                request.getBindingHash().getBytes(StandardCharsets.US_ASCII),
                OidcSecrets.sha256Hex(binding).getBytes(StandardCharsets.US_ASCII));
        if (!bindingMatches || request.getPurpose() != purpose || request.isConsumed() || request.isExpiredAt(now)) {
            throw new TelegramAuthException(TelegramAuthException.Reason.INVALID_STATE);
        }
        if (!requests.markConsumed(request.getId(), now)) {
            throw new TelegramAuthException(TelegramAuthException.Reason.INVALID_STATE);
        }
        return request;
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new TelegramAuthException(TelegramAuthException.Reason.NOT_CONFIGURED);
        }
    }

    private static void requireBinding(String binding) {
        Objects.requireNonNull(binding, "binding");
        if (binding.length() < MIN_BINDING_LENGTH || binding.length() > MAX_BINDING_LENGTH) {
            throw new TelegramAuthException(TelegramAuthException.Reason.INVALID_REQUEST);
        }
    }
}
