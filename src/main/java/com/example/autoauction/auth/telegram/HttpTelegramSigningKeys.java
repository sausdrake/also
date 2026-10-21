package com.example.autoauction.auth.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * AUTH-TELEGRAM-01: fetches the JWKS from the configured jwks_uri and caches it. An unknown key id (rotation)
 * triggers a refetch, at most once per {@link #MIN_REFRESH_INTERVAL}, so a flood of forged key ids cannot turn
 * this into a request amplifier.
 */
@Slf4j
@Component
public class HttpTelegramSigningKeys implements TelegramSigningKeys {

    static final Duration MIN_REFRESH_INTERVAL = Duration.ofMinutes(1);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final TelegramOidcProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Clock clock;
    private volatile Map<String, PublicKey> keys = Map.of();
    private volatile Instant lastFetch = Instant.EPOCH;

    public HttpTelegramSigningKeys(TelegramOidcProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Optional<PublicKey> find(String keyId) {
        if (keyId == null) {
            return Optional.empty();
        }
        PublicKey key = keys.get(keyId);
        if (key == null && refreshAllowed()) {
            refresh();
            key = keys.get(keyId);
        }
        return Optional.ofNullable(key);
    }

    private synchronized boolean refreshAllowed() {
        Instant now = clock.instant();
        if (now.isBefore(lastFetch.plus(MIN_REFRESH_INTERVAL))) {
            return false;
        }
        lastFetch = now;
        return true;
    }

    private void refresh() {
        try {
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(properties.jwksUri()))
                    .timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Telegram JWKS fetch failed: HTTP {}", response.statusCode());
                return;
            }
            Map<String, PublicKey> fresh = new HashMap<>();
            for (JsonNode jwk : objectMapper.readTree(response.body()).path("keys")) {
                PublicKey key = JwkPublicKeys.toPublicKey(jwk);
                if (key != null && jwk.hasNonNull("kid")) {
                    fresh.put(jwk.get("kid").asText(), key);
                }
            }
            keys = Map.copyOf(fresh);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Telegram JWKS fetch failed: {}", e.getClass().getSimpleName());
        }
    }
}
