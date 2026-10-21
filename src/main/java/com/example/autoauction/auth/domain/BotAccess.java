package com.example.autoauction.auth.domain;

/**
 * AUTH-TELEGRAM-01: whether the Telegram bot may write to the user - a capability separate from being
 * authenticated. Nothing requests it yet (TELEGRAM-NOTIFY-01), so every identity starts NOT_REQUESTED.
 */
public enum BotAccess {
    NOT_REQUESTED,
    GRANTED
}
