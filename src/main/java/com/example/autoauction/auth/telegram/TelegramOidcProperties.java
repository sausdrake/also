package com.example.autoauction.auth.telegram;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AUTH-TELEGRAM-01: Telegram OIDC configuration, from the environment only. The client secret never leaves the
 * backend (no DTO, no log, no frontend variable). Endpoints and issuer default to Telegram's published discovery
 * document (https://oauth.telegram.org/.well-known/openid-configuration); they are overridable only so an isolated
 * test environment can point at a fake provider. Login is disabled while client id, secret or redirect URI is unset.
 */
@Component
public class TelegramOidcProperties {

    public static final String TELEGRAM_ISSUER = "https://oauth.telegram.org";

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String issuer;
    private final String authorizationEndpoint;
    private final String tokenEndpoint;
    private final String jwksUri;

    public TelegramOidcProperties(
            @Value("${telegram.oidc.client-id:}") String clientId,
            @Value("${telegram.oidc.client-secret:}") String clientSecret,
            @Value("${telegram.oidc.redirect-uri:}") String redirectUri,
            @Value("${telegram.oidc.issuer:" + TELEGRAM_ISSUER + "}") String issuer,
            @Value("${telegram.oidc.authorization-endpoint:" + TELEGRAM_ISSUER + "/auth}") String authorizationEndpoint,
            @Value("${telegram.oidc.token-endpoint:" + TELEGRAM_ISSUER + "/token}") String tokenEndpoint,
            @Value("${telegram.oidc.jwks-uri:" + TELEGRAM_ISSUER + "/.well-known/jwks.json}") String jwksUri) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret;
        this.redirectUri = redirectUri == null ? "" : redirectUri.trim();
        this.issuer = issuer;
        this.authorizationEndpoint = authorizationEndpoint;
        this.tokenEndpoint = tokenEndpoint;
        this.jwksUri = jwksUri;
    }

    public boolean isConfigured() {
        return !clientId.isEmpty() && !clientSecret.isBlank() && !redirectUri.isEmpty();
    }

    public String clientId() { return clientId; }
    public String clientSecret() { return clientSecret; }
    public String redirectUri() { return redirectUri; }
    public String issuer() { return issuer; }
    public String authorizationEndpoint() { return authorizationEndpoint; }
    public String tokenEndpoint() { return tokenEndpoint; }
    public String jwksUri() { return jwksUri; }

    @Override
    public String toString() {
        return "TelegramOidcProperties[clientId=" + clientId + ", configured=" + isConfigured() + "]";   // never the secret
    }
}
