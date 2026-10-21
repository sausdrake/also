package com.example.autoauction.auth.telegram;

import com.example.autoauction.auth.application.TelegramAuthException;
import com.example.autoauction.auth.domain.VerifiedExternalIdentity;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.example.autoauction.auth.telegram.TelegramTestKeys.RSA_KID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AUTH-TELEGRAM-01: server-side ID token validation (T4-T8, T13/T14, T17/T18 and algorithm attacks). */
class TelegramIdTokenValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
    private static final String NONCE = "server-nonce";
    private final TelegramIdTokenValidator validator = new TelegramIdTokenValidator(
            TelegramTestKeys.signingKeys(), TelegramTestKeys.properties(), Clock.fixed(NOW, ZoneOffset.UTC));

    private static Map<String, Object> claims(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private String token(Map<String, Object> extra) {
        return TelegramTestKeys.idToken("tg-sub-1", NOW, extra);
    }

    private void refused(String token) {
        assertThatThrownBy(() -> validator.validate(token, NONCE))
                .isInstanceOf(TelegramAuthException.class)
                .extracting(e -> ((TelegramAuthException) e).getReason())
                .isEqualTo(TelegramAuthException.Reason.INVALID_TOKEN);
    }

    @Test
    @DisplayName("a valid RS256 token: sub is the identity, name / @username are display data")
    void validToken() {
        VerifiedExternalIdentity identity = validator.validate(token(claims("nonce", NONCE)), NONCE);
        assertThat(identity.subject()).isEqualTo("tg-sub-1");
        assertThat(identity.displayName()).isEqualTo("Test User");
        assertThat(identity.providerUsername()).isEqualTo("test_user");
        assertThat(identity.phoneNumber()).isNull();
        assertThat(identity.toString()).doesNotContain("tg-sub-1");
    }

    @Test
    @DisplayName("a valid ES256 token is accepted too")
    void validEs256() {
        String es = TelegramTestKeys.builder("tg-sub-ec", NOW, null).setHeaderParam("kid", TelegramTestKeys.EC_KID)
                .signWith(TelegramTestKeys.EC.getPrivate(), SignatureAlgorithm.ES256).compact();
        assertThat(validator.validate(es, NONCE).subject()).isEqualTo("tg-sub-ec");
    }

    @Test
    @DisplayName("T4: a token signed by a key Telegram never published is refused")
    void foreignSignature() {
        refused(TelegramTestKeys.builder("tg-sub-1", NOW, null).setHeaderParam("kid", RSA_KID)
                .signWith(TelegramTestKeys.FOREIGN_RSA.getPrivate(), SignatureAlgorithm.RS256).compact());
    }

    @Test
    @DisplayName("T4: a token whose payload was modified after signing is refused")
    void tamperedPayload() {
        String[] parts = token(null).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8).replace("tg-sub-1", "tg-sub-2");
        refused(parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2]);
    }

    @Test
    @DisplayName("alg none (unsigned) is refused")
    void unsigned() {
        refused(TelegramTestKeys.builder("tg-sub-1", NOW, null).setHeaderParam("kid", RSA_KID).compact());
    }

    @Test
    @DisplayName("HS256 signed with the public key bytes (algorithm confusion) is refused")
    void hmacConfusion() {
        byte[] publicBytes = TelegramTestKeys.RSA.getPublic().getEncoded();
        refused(TelegramTestKeys.builder("tg-sub-1", NOW, null).setHeaderParam("kid", RSA_KID)
                .signWith(Keys.hmacShaKeyFor(publicBytes), SignatureAlgorithm.HS256).compact());
    }

    @Test
    @DisplayName("an ES256 header on the RSA key id, an unknown key id or no key id is refused")
    void keyAlgorithmMismatch() {
        refused(TelegramTestKeys.builder("tg-sub-1", NOW, null).setHeaderParam("kid", RSA_KID)
                .signWith(TelegramTestKeys.EC.getPrivate(), SignatureAlgorithm.ES256).compact());
        refused(TelegramTestKeys.builder("tg-sub-1", NOW, null).setHeaderParam("kid", "unknown")
                .signWith(TelegramTestKeys.RSA.getPrivate(), SignatureAlgorithm.RS256).compact());
        refused(TelegramTestKeys.builder("tg-sub-1", NOW, null)
                .signWith(TelegramTestKeys.RSA.getPrivate(), SignatureAlgorithm.RS256).compact());
    }

    @Test
    @DisplayName("T5: wrong issuer is refused")
    void wrongIssuer() {
        refused(token(claims("iss", "https://oauth.telegram.org.evil.example")));
        refused(token(claims("iss", null)));
    }

    @Test
    @DisplayName("T6: wrong or missing audience is refused; an audience list containing our client id is accepted")
    void audience() {
        refused(token(claims("aud", "7000000002")));
        refused(token(claims("aud", null)));
        refused(token(claims("aud", List.of("other"))));
        assertThat(validator.validate(token(claims("aud", List.of("other", TelegramTestKeys.CLIENT_ID))), NONCE).subject())
                .isEqualTo("tg-sub-1");
    }

    @Test
    @DisplayName("T7: expired (beyond 60 s skew) is refused; within the skew accepted; no exp refused")
    void expiry() {
        refused(token(claims("exp", NOW.minusSeconds(61).getEpochSecond())));
        assertThat(validator.validate(token(claims("exp", NOW.minusSeconds(30).getEpochSecond())), NONCE)).isNotNull();
        refused(token(claims("exp", null)));
    }

    @Test
    @DisplayName("iat in the future beyond the skew, or missing, is refused")
    void issuedAt() {
        refused(token(claims("iat", NOW.plusSeconds(120).getEpochSecond())));
        assertThat(validator.validate(token(claims("iat", NOW.plusSeconds(30).getEpochSecond())), NONCE)).isNotNull();
        refused(token(claims("iat", null)));
    }

    @Test
    @DisplayName("T8: a nonce that is not ours is refused; no nonce (Telegram server flow) is accepted")
    void nonce() {
        refused(token(claims("nonce", "attacker-nonce")));
        assertThat(validator.validate(token(null), NONCE).subject()).isEqualTo("tg-sub-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("missing / blank sub is refused")
    void blankSubject(String sub) {
        refused(token(claims("sub", sub)));
    }

    @Test
    void missingSubjectAndGarbage() {
        refused(token(claims("sub", null)));
        refused("not-a-jwt");
        refused("");
        refused(null);
    }

    @Test
    @DisplayName("T17/T18: a phone is taken only when the provider marked it verified")
    void phone() {
        assertThat(validator.validate(token(claims("phone_number", "+996700000001", "phone_number_verified", true)), NONCE).phoneNumber())
                .isEqualTo("+996700000001");
        assertThat(validator.validate(token(claims("phone_number", "+996700000001", "phone_number_verified", false)), NONCE).phoneNumber()).isNull();
        assertThat(validator.validate(token(claims("phone_number", "+996700000001")), NONCE).phoneNumber()).isNull();
    }

    @Test
    @DisplayName("T13/T14: a changed name / @username is still the same identity (sub)")
    void displayDataIsNotIdentity() {
        VerifiedExternalIdentity a = validator.validate(token(claims("preferred_username", "old_name", "name", "Old")), NONCE);
        VerifiedExternalIdentity b = validator.validate(token(claims("preferred_username", "new_name", "name", "New")), NONCE);
        assertThat(a.subject()).isEqualTo(b.subject());
        assertThat(a.providerUsername()).isNotEqualTo(b.providerUsername());
    }
}
