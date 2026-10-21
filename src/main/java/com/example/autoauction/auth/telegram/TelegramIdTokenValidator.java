package com.example.autoauction.auth.telegram;

import com.example.autoauction.auth.application.TelegramAuthException;
import com.example.autoauction.auth.domain.ExternalIdentityProvider;
import com.example.autoauction.auth.domain.VerifiedExternalIdentity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SigningKeyResolverAdapter;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.Set;

/**
 * AUTH-TELEGRAM-01: server-side validation of a Telegram ID token - nothing from it is trusted before every
 * check passes:
 * <ul>
 *   <li>signature by a key from Telegram's JWKS, matched by {@code kid}; algorithm only RS256 / ES256 and equal to
 *       the key's own algorithm (no "none", no HMAC with a public key);</li>
 *   <li>{@code iss} = the configured issuer (https://oauth.telegram.org); {@code aud} contains our client id;</li>
 *   <li>{@code exp} in the future and {@code iat} not in the future (60 s clock skew), {@code sub} present;</li>
 *   <li>{@code nonce}: Telegram documents it only for its JS library, so a token without one is accepted (the token
 *       comes from our own back-channel code exchange, bound by state + PKCE); a token WITH a nonce must carry
 *       exactly ours.</li>
 * </ul>
 * Only {@code sub} identifies the person; name / username / phone are display data.
 */
@Component
public class TelegramIdTokenValidator {

    static final long CLOCK_SKEW_SECONDS = 60;
    private static final Set<String> ALLOWED_ALGORITHMS = Set.of(JwkPublicKeys.RS256, JwkPublicKeys.ES256);

    private final TelegramSigningKeys signingKeys;
    private final TelegramOidcProperties properties;
    private final Clock clock;

    public TelegramIdTokenValidator(TelegramSigningKeys signingKeys, TelegramOidcProperties properties, Clock clock) {
        this.signingKeys = signingKeys;
        this.properties = properties;
        this.clock = clock;
    }

    public VerifiedExternalIdentity validate(String idToken, String expectedNonce) {
        Claims claims = verifiedClaims(idToken);
        Instant now = clock.instant();

        if (!properties.issuer().equals(claims.getIssuer())) {
            throw invalid();
        }
        if (!audienceContains(claims.get("aud"), properties.clientId())) {
            throw invalid();
        }
        Date expiration = claims.getExpiration();
        if (expiration == null || !expiration.toInstant().plusSeconds(CLOCK_SKEW_SECONDS).isAfter(now)) {
            throw invalid();
        }
        Date issuedAt = claims.getIssuedAt();
        if (issuedAt == null || issuedAt.toInstant().isAfter(now.plusSeconds(CLOCK_SKEW_SECONDS))) {
            throw invalid();
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > 128) {
            throw invalid();
        }
        Object nonce = claims.get("nonce");
        if (nonce != null && !nonce.toString().equals(expectedNonce)) {
            throw invalid();
        }
        String phone = Boolean.TRUE.equals(claims.get("phone_number_verified", Boolean.class))
                ? text(claims.get("phone_number"), 32) : null;
        return new VerifiedExternalIdentity(ExternalIdentityProvider.TELEGRAM, subject,
                text(claims.get("name"), 255), text(claims.get("preferred_username"), 64), phone);
    }

    private Claims verifiedClaims(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            throw invalid();
        }
        try {
            return Jwts.parserBuilder()
                    // exp / iat are checked above against our Clock; jjwt's own exp check is relaxed to the same skew
                    .setAllowedClockSkewSeconds(CLOCK_SKEW_SECONDS)
                    .setClock(() -> Date.from(clock.instant()))
                    .setSigningKeyResolver(new SigningKeyResolverAdapter() {
                        @Override
                        public Key resolveSigningKey(JwsHeader header, Claims ignored) {
                            String algorithm = header.getAlgorithm();
                            if (!ALLOWED_ALGORITHMS.contains(algorithm)) {
                                throw invalid();
                            }
                            Key key = signingKeys.find(header.getKeyId()).orElseThrow(TelegramIdTokenValidator::invalid);
                            if (!algorithm.equals(JwkPublicKeys.algorithmOf((java.security.PublicKey) key))) {
                                throw invalid();
                            }
                            return key;
                        }
                    })
                    .build()
                    .parseClaimsJws(idToken)
                    .getBody();
        } catch (TelegramAuthException e) {
            throw e;
        } catch (JwtException | IllegalArgumentException | ClassCastException e) {
            throw invalid();
        }
    }

    static boolean audienceContains(Object aud, String clientId) {
        if (aud instanceof Collection<?> values) {
            return values.stream().anyMatch(v -> clientId.equals(String.valueOf(v)));
        }
        return aud != null && clientId.equals(String.valueOf(aud));
    }

    private static String text(Object value, int max) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : (s.length() > max ? s.substring(0, max) : s);
    }

    private static TelegramAuthException invalid() {
        return new TelegramAuthException(TelegramAuthException.Reason.INVALID_TOKEN);
    }
}
