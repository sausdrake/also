package com.example.autoauction.auth.telegram;

import com.example.autoauction.auth.application.TelegramAuthException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AUTH-TELEGRAM-01: POST {token_endpoint} (application/x-www-form-urlencoded, HTTP Basic client_id:client_secret)
 * with grant_type=authorization_code, code, redirect_uri, client_id, code_verifier - per Telegram's OIDC
 * documentation. Neither the code, the verifier, the secret nor the returned token is ever logged.
 */
@Slf4j
@Component
public class HttpTelegramTokenClient implements TelegramTokenClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final TelegramOidcProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public HttpTelegramTokenClient(TelegramOidcProperties properties) {
        this.properties = properties;
    }

    @Override
    public String exchangeCode(String code, String codeVerifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", properties.redirectUri());
        form.put("client_id", properties.clientId());
        form.put("code_verifier", codeVerifier);
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        String basic = Base64.getEncoder().encodeToString(
                (properties.clientId() + ":" + properties.clientSecret()).getBytes(StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(properties.tokenEndpoint()))
                            .timeout(TIMEOUT)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Authorization", "Basic " + basic)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Telegram token exchange refused: HTTP {}", response.statusCode());
                throw new TelegramAuthException(TelegramAuthException.Reason.INVALID_CODE);
            }
            JsonNode json = objectMapper.readTree(response.body());
            String idToken = json.path("id_token").asText("");
            if (idToken.isEmpty()) {
                throw new TelegramAuthException(TelegramAuthException.Reason.INVALID_TOKEN);
            }
            return idToken;
        } catch (TelegramAuthException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Telegram token exchange failed: {}", e.getClass().getSimpleName());
            throw new TelegramAuthException(TelegramAuthException.Reason.PROVIDER_UNAVAILABLE);
        }
    }
}
