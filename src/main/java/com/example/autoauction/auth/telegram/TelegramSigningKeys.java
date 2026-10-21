package com.example.autoauction.auth.telegram;

import java.security.PublicKey;
import java.util.Optional;

/** AUTH-TELEGRAM-01: Telegram's ID-token signing keys (JWKS), by key id. */
public interface TelegramSigningKeys {

    Optional<PublicKey> find(String keyId);
}
