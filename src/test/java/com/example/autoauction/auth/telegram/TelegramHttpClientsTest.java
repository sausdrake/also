package com.example.autoauction.auth.telegram;

import com.example.autoauction.auth.application.TelegramAuthException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AUTH-TELEGRAM-01: JWK parsing, the JWKS cache and the token-endpoint client against a local HTTP server. */
class TelegramHttpClientsTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger jwksHits = new AtomicInteger();
    private volatile int tokenStatus = 200;
    private volatile String tokenBody = "{\"access_token\":\"a\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"id_token\":\"header.payload.sig\"}";

    private static String b64(BigInteger v) {
        byte[] bytes = v.toByteArray();
        if (bytes[0] == 0) bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String jwks() {
        RSAPublicKey rsa = (RSAPublicKey) TelegramTestKeys.RSA.getPublic();
        ECPublicKey ec = (ECPublicKey) TelegramTestKeys.EC.getPublic();
        return "{\"keys\":["
                + "{\"kty\":\"RSA\",\"kid\":\"oidc-1\",\"alg\":\"RS256\",\"n\":\"" + b64(rsa.getModulus()) + "\",\"e\":\"" + b64(rsa.getPublicExponent()) + "\"},"
                + "{\"kty\":\"EC\",\"kid\":\"oidc-es256-1\",\"alg\":\"ES256\",\"use\":\"sig\",\"crv\":\"P-256\",\"x\":\""
                + b64(ec.getW().getAffineX()) + "\",\"y\":\"" + b64(ec.getW().getAffineY()) + "\"},"
                + "{\"kty\":\"OKP\",\"kid\":\"oidc-eddsa-1\",\"alg\":\"EdDSA\",\"use\":\"sig\",\"crv\":\"Ed25519\",\"x\":\"AAAA\"},"
                + "{\"kty\":\"RSA\",\"kid\":\"enc-1\",\"use\":\"enc\",\"n\":\"AQAB\",\"e\":\"AQAB\"}"
                + "]}";
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", ex -> {
            jwksHits.incrementAndGet();
            byte[] body = jwks().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/token", ex -> {
            requests.add(ex.getRequestMethod() + "|" + ex.getRequestHeaders().getFirst("Authorization") + "|"
                    + ex.getRequestHeaders().getFirst("Content-Type") + "|" + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = tokenBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(tokenStatus, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private TelegramOidcProperties properties() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new TelegramOidcProperties(TelegramTestKeys.CLIENT_ID, "s3cr3t", "https://also.example/auth/telegram/callback",
                TelegramTestKeys.ISSUER, base + "/auth", base + "/token", base + "/jwks");
    }

    @Test
    @DisplayName("JWKS: RSA and EC P-256 signing keys are parsed to the same public keys; EdDSA / encryption keys skipped")
    void jwksParsing() {
        HttpTelegramSigningKeys keys = new HttpTelegramSigningKeys(properties(), Clock.systemUTC());
        assertThat(keys.find("oidc-1")).contains(TelegramTestKeys.RSA.getPublic());
        assertThat(keys.find("oidc-es256-1")).contains(TelegramTestKeys.EC.getPublic());
        assertThat(keys.find("oidc-eddsa-1")).isEmpty();
        assertThat(keys.find("enc-1")).isEmpty();
        assertThat(keys.find(null)).isEmpty();
    }

    @Test
    @DisplayName("JWKS: cached; an unknown kid refetches at most once per minute (no amplification)")
    void jwksCache() {
        Clock[] clock = {Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneId.of("UTC"))};
        HttpTelegramSigningKeys keys = new HttpTelegramSigningKeys(properties(), new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return clock[0].instant(); }
        });
        keys.find("oidc-1");
        keys.find("oidc-1");
        for (int i = 0; i < 20; i++) keys.find("forged-" + i);
        assertThat(jwksHits.get()).isEqualTo(1);
        clock[0] = Clock.offset(clock[0], java.time.Duration.ofSeconds(61));
        keys.find("forged-again");
        assertThat(jwksHits.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("token exchange: POST form, HTTP Basic client_id:secret, PKCE verifier and redirect_uri sent; id_token returned")
    void tokenExchange() throws Exception {
        HttpTelegramTokenClient client = new HttpTelegramTokenClient(properties());
        assertThat(client.exchangeCode("the-code", "the-verifier")).isEqualTo("header.payload.sig");
        String[] r = requests.get(0).split("\\|", 4);
        assertThat(r[0]).isEqualTo("POST");
        assertThat(r[1]).isEqualTo("Basic " + Base64.getEncoder().encodeToString(
                (TelegramTestKeys.CLIENT_ID + ":s3cr3t").getBytes(StandardCharsets.UTF_8)));
        assertThat(r[2]).isEqualTo("application/x-www-form-urlencoded");
        assertThat(r[3]).contains("grant_type=authorization_code", "code=the-code", "code_verifier=the-verifier",
                "client_id=" + TelegramTestKeys.CLIENT_ID, "redirect_uri=https%3A%2F%2Falso.example%2Fauth%2Ftelegram%2Fcallback");
        assertThat(r[3]).doesNotContain("s3cr3t");
    }

    @Test
    @DisplayName("token exchange: a refusal or a response without id_token is a safe error, never the provider's text")
    void tokenRefusals() {
        HttpTelegramTokenClient client = new HttpTelegramTokenClient(properties());
        tokenStatus = 400;
        tokenBody = "{\"error\":\"invalid_grant\",\"error_description\":\"secret provider detail\"}";
        assertThatThrownBy(() -> client.exchangeCode("c", "v")).isInstanceOf(TelegramAuthException.class)
                .hasMessageNotContaining("provider detail")
                .extracting(e -> ((TelegramAuthException) e).getReason()).isEqualTo(TelegramAuthException.Reason.INVALID_CODE);
        tokenStatus = 200;
        tokenBody = "{\"access_token\":\"a\"}";
        assertThatThrownBy(() -> client.exchangeCode("c", "v")).isInstanceOf(TelegramAuthException.class)
                .extracting(e -> ((TelegramAuthException) e).getReason()).isEqualTo(TelegramAuthException.Reason.INVALID_TOKEN);
        server.stop(0);
        assertThatThrownBy(() -> client.exchangeCode("c", "v")).isInstanceOf(TelegramAuthException.class)
                .extracting(e -> ((TelegramAuthException) e).getReason()).isEqualTo(TelegramAuthException.Reason.PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("AUTH-TG-ERR-01: HTTP 200 {error}: invalid_grant -> retry, other codes -> rejected; only the safe code is logged")
    void oauthErrorsWithHttp200() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(HttpTelegramTokenClient.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            HttpTelegramTokenClient client = new HttpTelegramTokenClient(properties());
            tokenStatus = 200;
            tokenBody = "{\"error\":\"invalid_client\",\"error_description\":\"client secret mismatch detail\"}";
            assertThatThrownBy(() -> client.exchangeCode("c", "v")).isInstanceOf(TelegramAuthException.class)
                    .hasMessageNotContaining("mismatch detail")
                    .extracting(e -> ((TelegramAuthException) e).getReason()).isEqualTo(TelegramAuthException.Reason.PROVIDER_REJECTED);
            tokenBody = "{\"error\":\"invalid_grant\",\"error_description\":\"code expired detail\"}";
            assertThatThrownBy(() -> client.exchangeCode("c", "v")).isInstanceOf(TelegramAuthException.class)
                    .extracting(e -> ((TelegramAuthException) e).getReason()).isEqualTo(TelegramAuthException.Reason.INVALID_CODE);
            tokenBody = "{\"error\":\"Weird <b>error</b> text\",\"id_token\":\"header.payload.sig\"}";
            assertThatThrownBy(() -> client.exchangeCode("c", "v")).isInstanceOf(TelegramAuthException.class)
                    .extracting(e -> ((TelegramAuthException) e).getReason()).isEqualTo(TelegramAuthException.Reason.PROVIDER_REJECTED);
            tokenBody = "<html>not json</html>";
            assertThatThrownBy(() -> client.exchangeCode("c", "v")).isInstanceOf(TelegramAuthException.class)
                    .extracting(e -> ((TelegramAuthException) e).getReason()).isEqualTo(TelegramAuthException.Reason.INVALID_TOKEN);
            tokenStatus = 401;
            tokenBody = "{\"error\":\"invalid_client\"}";
            assertThatThrownBy(() -> client.exchangeCode("c", "v")).isInstanceOf(TelegramAuthException.class)
                    .extracting(e -> ((TelegramAuthException) e).getReason()).isEqualTo(TelegramAuthException.Reason.PROVIDER_REJECTED);

            String logged = logs.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(logged).contains("OAuth error invalid_client", "OAuth error invalid_grant", "OAuth error <non-standard>");
            assertThat(logged).doesNotContain("detail", "Weird", "<b>", "s3cr3t", "header.payload.sig", "code_verifier");
        } finally {
            logger.detachAppender(logs);
        }
        // the user can tell "Telegram refused" from "the answer could not be checked"
        assertThat(TelegramAuthException.Reason.PROVIDER_REJECTED.message())
                .isNotEqualTo(TelegramAuthException.Reason.INVALID_TOKEN.message()).contains("Telegram отклонил");
    }

    @Test
    @DisplayName("properties: unconfigured without client id / secret / redirect; toString never shows the secret")
    void configuration() {
        assertThat(new TelegramOidcProperties("", "s", "r", "i", "a", "t", "j").isConfigured()).isFalse();
        assertThat(new TelegramOidcProperties("1", " ", "r", "i", "a", "t", "j").isConfigured()).isFalse();
        assertThat(new TelegramOidcProperties("1", "s", "", "i", "a", "t", "j").isConfigured()).isFalse();
        TelegramOidcProperties p = new TelegramOidcProperties("1", "very-secret", "r", "i", "a", "t", "j");
        assertThat(p.isConfigured()).isTrue();
        assertThat(p.toString()).doesNotContain("very-secret");
    }
}
