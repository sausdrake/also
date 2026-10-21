package com.example.autoauction.auth.telegram;

import com.example.autoauction.auth.application.TelegramAuthException;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AUTH-TELEGRAM-01 test double for Telegram's authorization + token endpoints (profile "test"): the test "approves"
 * an authorization URL for a subject and gets a one-time code; the token exchange then checks the PKCE S256
 * verifier against the challenge from that URL, exactly like the real provider, and returns a token signed with
 * {@link TelegramTestKeys}. Codes are single use.
 */
@Component
@Primary
@Profile("test")
public class FakeTelegramProvider implements TelegramTokenClient, TelegramSigningKeys {

    private record Grant(String challenge, String idToken) {}

    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    /** Simulates the user approving {@code authorizationUrl}; returns {code, state} for the callback. */
    public Map<String, String> approve(String authorizationUrl, String subject, Map<String, Object> extraClaims) {
        Map<String, String> query = query(authorizationUrl);
        Map<String, Object> claims = new HashMap<>();
        if (extraClaims != null) claims.putAll(extraClaims);
        claims.putIfAbsent("nonce", query.get("nonce"));
        return approveWithToken(authorizationUrl, TelegramTestKeys.idToken(subject, Instant.now(), claims));
    }

    /** Like {@link #approve}, but the provider will return exactly {@code idToken}. */
    public Map<String, String> approveWithToken(String authorizationUrl, String idToken) {
        Map<String, String> query = query(authorizationUrl);
        String code = "code-" + java.util.UUID.randomUUID();
        grants.put(code, new Grant(query.get("code_challenge"), idToken));
        return Map.of("code", code, "state", query.get("state"));
    }

    @Override
    public String exchangeCode(String code, String codeVerifier) {
        Grant grant = grants.remove(code);                    // single use
        if (grant == null || !s256(codeVerifier).equals(grant.challenge())) {
            throw new TelegramAuthException(TelegramAuthException.Reason.INVALID_CODE);
        }
        return grant.idToken();
    }

    @Override
    public java.util.Optional<java.security.PublicKey> find(String keyId) {
        return TelegramTestKeys.signingKeys().find(keyId);
    }

    public static Map<String, String> query(String url) {
        Map<String, String> result = new HashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            result.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static String s256(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
