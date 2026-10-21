package com.example.autoauction.auth.telegram;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

/**
 * AUTH-TELEGRAM-01 test fixtures: throw-away signing keys generated per JVM (never a real Telegram key) and a
 * builder for ID tokens shaped like Telegram's. Production secrets never appear in tests.
 */
public final class TelegramTestKeys {

    public static final String CLIENT_ID = "7000000001";
    public static final String ISSUER = TelegramOidcProperties.TELEGRAM_ISSUER;
    public static final String RSA_KID = "test-rs256";
    public static final String EC_KID = "test-es256";

    public static final KeyPair RSA = generate("RSA", null);
    public static final KeyPair EC = generate("EC", "secp256r1");
    /** A key Telegram never published - tokens signed with it must be refused. */
    public static final KeyPair FOREIGN_RSA = generate("RSA", null);

    private TelegramTestKeys() {
    }

    public static TelegramSigningKeys signingKeys() {
        return kid -> Optional.ofNullable(switch (kid == null ? "" : kid) {
            case RSA_KID -> (PublicKey) RSA.getPublic();
            case EC_KID -> (PublicKey) EC.getPublic();
            default -> null;
        });
    }

    public static TelegramOidcProperties properties() {
        return new TelegramOidcProperties(CLIENT_ID, "test-secret", "http://localhost:5173/auth/telegram/callback",
                ISSUER, ISSUER + "/auth", ISSUER + "/token", ISSUER + "/.well-known/jwks.json");
    }

    /** A valid token for {@code subject} at {@code now}; adjust claims with {@code extra} (null value removes). */
    public static String idToken(String subject, Instant now, Map<String, Object> extra) {
        return builder(subject, now, extra).setHeaderParam("kid", RSA_KID).signWith(RSA.getPrivate(), SignatureAlgorithm.RS256).compact();
    }

    public static JwtBuilder builder(String subject, Instant now, Map<String, Object> extra) {
        java.util.HashMap<String, Object> claims = new java.util.HashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", CLIENT_ID);
        claims.put("sub", subject);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plusSeconds(3600).getEpochSecond());
        claims.put("name", "Test User");
        claims.put("preferred_username", "test_user");
        if (extra != null) {
            extra.forEach((k, v) -> {
                if (v == null) claims.remove(k); else claims.put(k, v);
            });
        }
        return Jwts.builder().setClaims(claims);
    }

    static Date date(Instant instant) {
        return Date.from(instant);
    }

    private static KeyPair generate(String algorithm, String curve) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
            if (curve != null) {
                generator.initialize(new ECGenParameterSpec(curve));
            } else {
                generator.initialize(2048);
            }
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
