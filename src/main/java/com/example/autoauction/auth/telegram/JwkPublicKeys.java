package com.example.autoauction.auth.telegram;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * AUTH-TELEGRAM-01: JWK (RFC 7517) -> java.security.PublicKey for the two algorithms accepted for Telegram ID
 * tokens: RS256 (RSA, Telegram's default) and ES256 (EC P-256). EdDSA / ES256K keys are not usable here (the
 * offline jjwt 0.11 cannot verify them) and are skipped - BotFather's default RS256 must be kept.
 */
final class JwkPublicKeys {

    static final String RS256 = "RS256";
    static final String ES256 = "ES256";

    private JwkPublicKeys() {
    }

    /** The key, or null when the JWK is not an RS256 / ES256 signing key. */
    static PublicKey toPublicKey(JsonNode jwk) throws GeneralSecurityException {
        String kty = jwk.path("kty").asText("");
        String alg = jwk.path("alg").asText("");
        String use = jwk.path("use").asText("sig");
        if (!"sig".equals(use)) {
            return null;
        }
        if ("RSA".equals(kty) && (alg.isEmpty() || RS256.equals(alg))) {
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                    unsigned(jwk.path("n").asText()), unsigned(jwk.path("e").asText())));
        }
        if ("EC".equals(kty) && "P-256".equals(jwk.path("crv").asText()) && (alg.isEmpty() || ES256.equals(alg))) {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
            ECPoint point = new ECPoint(unsigned(jwk.path("x").asText()), unsigned(jwk.path("y").asText()));
            return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
        }
        return null;
    }

    /** The JWS algorithm a key may verify. */
    static String algorithmOf(PublicKey key) {
        return "RSA".equals(key.getAlgorithm()) ? RS256 : ES256;
    }

    private static BigInteger unsigned(String base64Url) {
        if (base64Url.isEmpty()) {
            throw new IllegalArgumentException("JWK без параметров ключа");
        }
        return new BigInteger(1, Base64.getUrlDecoder().decode(base64Url));
    }
}
