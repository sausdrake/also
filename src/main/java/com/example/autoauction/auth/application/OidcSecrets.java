package com.example.autoauction.auth.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** AUTH-TELEGRAM-01: random OIDC values and their hashes (state, nonce, PKCE S256, browser binding). */
final class OidcSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    private OidcSecrets() {
    }

    /** 32 random bytes, base64url without padding (43 chars) - a valid PKCE code_verifier too (RFC 7636: 43-128). */
    static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** RFC 7636 S256: BASE64URL(SHA256(ASCII(code_verifier))). */
    static String pkceChallenge(String codeVerifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(codeVerifier.getBytes(StandardCharsets.US_ASCII)));
    }

    static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
