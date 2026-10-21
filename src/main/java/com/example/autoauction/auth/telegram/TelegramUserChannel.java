package com.example.autoauction.auth.telegram;

/**
 * AUTH-TELEGRAM-01: the Telegram bot as a future communication channel - a boundary separate from
 * authentication (no bot call ever happens in the login path). Being signed in with Telegram does not mean the
 * bot may write to the user; nothing requests bot access yet, so {@link #canMessage} is false for everyone until
 * TELEGRAM-NOTIFY-01 implements the bot.
 */
public interface TelegramUserChannel {

    boolean canMessage(Long userId);
}
