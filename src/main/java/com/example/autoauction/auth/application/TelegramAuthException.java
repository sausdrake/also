package com.example.autoauction.auth.application;

import org.springframework.http.HttpStatus;

/**
 * AUTH-TELEGRAM-01: every Telegram sign-in / link refusal, as a safe internal code with a Russian message for the
 * user. The provider's own errors, tokens or identifiers are never put into it.
 */
public class TelegramAuthException extends RuntimeException {

    public enum Reason {
        NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "TELEGRAM_NOT_CONFIGURED",
                "Вход через Telegram сейчас недоступен."),
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "TELEGRAM_INVALID_REQUEST",
                "Некорректный запрос входа через Telegram."),
        INVALID_STATE(HttpStatus.BAD_REQUEST, "TELEGRAM_INVALID_STATE",
                "Ссылка входа через Telegram недействительна или устарела. Начните вход заново."),
        INVALID_CODE(HttpStatus.BAD_REQUEST, "TELEGRAM_INVALID_CODE",
                "Telegram не подтвердил вход. Начните вход заново."),
        INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "TELEGRAM_INVALID_TOKEN",
                "Не удалось проверить ответ Telegram. Начните вход заново."),
        PROVIDER_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "TELEGRAM_UNAVAILABLE",
                "Telegram сейчас не отвечает. Попробуйте позже."),
        IDENTITY_CONFLICT(HttpStatus.CONFLICT, "TELEGRAM_IDENTITY_CONFLICT",
                "Этот Telegram уже привязан к другому аккаунту AlSo или к аккаунту уже привязан другой Telegram."),
        LOGIN_NOT_ALLOWED(HttpStatus.FORBIDDEN, "TELEGRAM_LOGIN_NOT_ALLOWED",
                "Для этого аккаунта вход через Telegram недоступен. Войдите по логину и паролю.");

        private final HttpStatus status;
        private final String code;
        private final String message;

        Reason(HttpStatus status, String code, String message) {
            this.status = status;
            this.code = code;
            this.message = message;
        }

        public HttpStatus status() { return status; }
        public String code() { return code; }
        public String message() { return message; }
    }

    private final Reason reason;

    public TelegramAuthException(Reason reason) {
        super(reason.message());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
