package com.example.autoauction.auth.application;

import com.example.autoauction.auth.domain.OidcAuthorizationRequest;
import com.example.autoauction.auth.domain.OidcPurpose;
import com.example.autoauction.auth.domain.port.OidcAuthorizationRequestRepository;
import com.example.autoauction.auth.telegram.FakeTelegramProvider;
import com.example.autoauction.auth.telegram.TelegramOidcProperties;
import com.example.autoauction.auth.telegram.TelegramTestKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AUTH-TELEGRAM-01: the server side of the round trip - URL, PKCE, state binding, expiry, single use. */
class TelegramAuthorizationServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-22T10:00:00Z");
    private static final String BINDING = "b".repeat(43);

    /** In-memory repository with the same single-use semantics as the conditional UPDATE. */
    static class Repo implements OidcAuthorizationRequestRepository {
        final Map<String, OidcAuthorizationRequest> byState = new HashMap<>();
        final Map<Long, OffsetDateTime> consumed = new HashMap<>();
        long ids = 1;
        @Override public OidcAuthorizationRequest save(OidcAuthorizationRequest r) {
            OidcAuthorizationRequest s = new OidcAuthorizationRequest(ids++, r.getStateHash(), r.getBindingHash(), r.getCodeVerifier(),
                    r.getNonce(), r.getPurpose(), r.getLinkUserId(), r.getScope(), r.getCreatedAt(), r.getExpiresAt(), null);
            byState.put(s.getStateHash(), s);
            return s;
        }
        @Override public Optional<OidcAuthorizationRequest> findByStateHash(String h) {
            OidcAuthorizationRequest r = byState.get(h);
            if (r == null) return Optional.empty();
            return Optional.of(new OidcAuthorizationRequest(r.getId(), r.getStateHash(), r.getBindingHash(), r.getCodeVerifier(),
                    r.getNonce(), r.getPurpose(), r.getLinkUserId(), r.getScope(), r.getCreatedAt(), r.getExpiresAt(), consumed.get(r.getId())));
        }
        @Override public boolean markConsumed(Long id, OffsetDateTime at) { return consumed.putIfAbsent(id, at) == null; }
        @Override public int deleteExpiredBefore(OffsetDateTime before) { return 0; }
    }

    private Repo repo;
    private Instant now;
    private TelegramAuthorizationService service;

    @BeforeEach
    void setUp() {
        repo = new Repo();
        now = T0;
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now; }
        };
        service = new TelegramAuthorizationService(repo, TelegramTestKeys.properties(), clock);
    }

    private static void refused(Runnable r, TelegramAuthException.Reason reason) {
        assertThatThrownBy(r::run).isInstanceOf(TelegramAuthException.class)
                .extracting(e -> ((TelegramAuthException) e).getReason()).isEqualTo(reason);
    }

    @Test
    @DisplayName("the URL: Telegram auth endpoint, code flow, S256 challenge of the stored verifier, state + nonce, no secret")
    void authorizationUrl() {
        TelegramAuthorizationService.Started started = service.start(OidcPurpose.LOGIN, null, BINDING, false);
        Map<String, String> q = FakeTelegramProvider.query(started.authorizationUrl());
        assertThat(started.authorizationUrl()).startsWith(TelegramTestKeys.ISSUER + "/auth?");
        assertThat(q).containsEntry("response_type", "code").containsEntry("code_challenge_method", "S256")
                .containsEntry("scope", "openid profile").containsEntry("state", started.state())
                .containsEntry("redirect_uri", "http://localhost:5173/auth/telegram/callback");
        OidcAuthorizationRequest stored = repo.byState.values().iterator().next();
        assertThat(q.get("code_challenge")).isEqualTo(OidcSecrets.pkceChallenge(stored.getCodeVerifier()));
        assertThat(q.get("nonce")).isEqualTo(stored.getNonce());
        assertThat(stored.getStateHash()).isNotEqualTo(started.state()).isEqualTo(OidcSecrets.sha256Hex(started.state()));
        assertThat(stored.getBindingHash()).isEqualTo(OidcSecrets.sha256Hex(BINDING));
        assertThat(started.authorizationUrl()).doesNotContain("test-secret").doesNotContain(stored.getCodeVerifier());
        assertThat(stored.getExpiresAt()).isEqualTo(OffsetDateTime.ofInstant(T0, ZoneOffset.UTC).plusMinutes(10));
        assertThat(FakeTelegramProvider.query(service.start(OidcPurpose.LOGIN, null, BINDING, true).authorizationUrl()))
                .containsEntry("scope", "openid profile phone");
    }

    @Test
    @DisplayName("RFC 7636 appendix B test vector for S256")
    void pkceVector() {
        assertThat(OidcSecrets.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
        assertThat(OidcSecrets.random()).hasSize(43).isNotEqualTo(OidcSecrets.random());
    }

    @Test
    @DisplayName("consume: once, with the same binding and purpose, before expiry")
    void consume() {
        String state = service.start(OidcPurpose.LOGIN, null, BINDING, false).state();
        refused(() -> service.consume(state, "x".repeat(43), OidcPurpose.LOGIN), TelegramAuthException.Reason.INVALID_STATE);
        refused(() -> service.consume(state, BINDING, OidcPurpose.LINK), TelegramAuthException.Reason.INVALID_STATE);
        refused(() -> service.consume("other", BINDING, OidcPurpose.LOGIN), TelegramAuthException.Reason.INVALID_STATE);
        assertThat(service.consume(state, BINDING, OidcPurpose.LOGIN).getPurpose()).isEqualTo(OidcPurpose.LOGIN);
        refused(() -> service.consume(state, BINDING, OidcPurpose.LOGIN), TelegramAuthException.Reason.INVALID_STATE);
    }

    @Test
    @DisplayName("a callback that loses the race to consume the request (concurrent duplicate) is refused")
    void concurrentConsumeLost() {
        String state = service.start(OidcPurpose.LOGIN, null, BINDING, false).state();
        OidcAuthorizationRequest stored = repo.byState.values().iterator().next();
        repo.consumed.put(stored.getId(), OffsetDateTime.ofInstant(T0, ZoneOffset.UTC));   // consumed after our read
        Repo racing = new Repo() {
            @Override public Optional<OidcAuthorizationRequest> findByStateHash(String h) {
                return Optional.of(stored);                                                   // read before it was consumed
            }
            @Override public boolean markConsumed(Long id, OffsetDateTime at) { return false; }
        };
        TelegramAuthorizationService raced = new TelegramAuthorizationService(racing, TelegramTestKeys.properties(), Clock.fixed(T0, ZoneOffset.UTC));
        refused(() -> raced.consume(state, BINDING, OidcPurpose.LOGIN), TelegramAuthException.Reason.INVALID_STATE);
    }

    @Test
    @DisplayName("expiry boundary: valid at exactly 10 minutes, refused one second later")
    void expiry() {
        String a = service.start(OidcPurpose.LOGIN, null, BINDING, false).state();
        String b = service.start(OidcPurpose.LOGIN, null, BINDING, false).state();
        now = T0.plusSeconds(600);
        assertThat(service.consume(a, BINDING, OidcPurpose.LOGIN)).isNotNull();
        now = T0.plusSeconds(601);
        refused(() -> service.consume(b, BINDING, OidcPurpose.LOGIN), TelegramAuthException.Reason.INVALID_STATE);
    }

    @Test
    @DisplayName("a binding shorter than 32 or longer than 128 chars is refused; null / blank state refused")
    void inputs() {
        refused(() -> service.start(OidcPurpose.LOGIN, null, "short", false), TelegramAuthException.Reason.INVALID_REQUEST);
        refused(() -> service.start(OidcPurpose.LOGIN, null, "x".repeat(129), false), TelegramAuthException.Reason.INVALID_REQUEST);
        assertThat(service.start(OidcPurpose.LOGIN, null, "x".repeat(32), false)).isNotNull();
        refused(() -> service.consume(null, BINDING, OidcPurpose.LOGIN), TelegramAuthException.Reason.INVALID_STATE);
        refused(() -> service.consume(" ", BINDING, OidcPurpose.LOGIN), TelegramAuthException.Reason.INVALID_STATE);
        refused(() -> service.consume("s", null, OidcPurpose.LOGIN), TelegramAuthException.Reason.INVALID_STATE);
    }

    @Test
    @DisplayName("a LINK request carries its user; a LOGIN request never does")
    void linkUser() {
        assertThatThrownBy(() -> service.start(OidcPurpose.LINK, null, BINDING, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.start(OidcPurpose.LOGIN, 5L, BINDING, false)).isInstanceOf(IllegalArgumentException.class);
        String state = service.start(OidcPurpose.LINK, 5L, BINDING, false).state();
        assertThat(service.consume(state, BINDING, OidcPurpose.LINK).getLinkUserId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("not configured -> NOT_CONFIGURED on start and on callback")
    void notConfigured() {
        TelegramAuthorizationService off = new TelegramAuthorizationService(repo,
                new TelegramOidcProperties("", "", "", "i", "a", "t", "j"), Clock.systemUTC());
        refused(() -> off.start(OidcPurpose.LOGIN, null, BINDING, false), TelegramAuthException.Reason.NOT_CONFIGURED);
        refused(() -> off.consume("s", BINDING, OidcPurpose.LOGIN), TelegramAuthException.Reason.NOT_CONFIGURED);
    }
}
