package com.example.autoauction.auth.telegram;

/** AUTH-TELEGRAM-01: the back-channel code -> token exchange with Telegram's token endpoint. */
public interface TelegramTokenClient {

    /**
     * Exchanges an authorization code (with its PKCE verifier) for the raw ID token. Throws
     * {@link com.example.autoauction.auth.application.TelegramAuthException} on any refusal - the provider's own
     * error text is never passed to the user.
     */
    String exchangeCode(String code, String codeVerifier);
}
