package com.example.autoauction.auth.application;

/**
 * BE-AUTH-01: registration refused because the username or the email is already taken - either
 * found by the pre-check or reported by the users_username_key / users_email_key unique constraint
 * when a concurrent registration wins the race. Mapped to HTTP 409 by GlobalExceptionHandler.
 *
 * <p>Anti-enumeration: the public response carries only {@link #PUBLIC_CODE} and
 * {@link #PUBLIC_MESSAGE}, identical for a taken username and a taken email, so a client cannot
 * learn which one exists. {@link #getMessage()} is the same public text, so no handler can leak the
 * field by echoing it. {@link #getField()} / {@link #getReasonCode()} are server-side only (logs,
 * tests) and must never be put into a response body.
 */
public class RegistrationConflictException extends RuntimeException {

    public static final String PUBLIC_CODE = "REGISTRATION_CONFLICT";
    public static final String PUBLIC_MESSAGE = "Пользователь с указанными данными уже существует.";

    public enum Field {
        USERNAME("USERNAME_TAKEN"),
        EMAIL("EMAIL_TAKEN");

        private final String reasonCode;

        Field(String reasonCode) {
            this.reasonCode = reasonCode;
        }
    }

    private final Field field;

    public RegistrationConflictException(Field field) {
        super(PUBLIC_MESSAGE);
        this.field = field;
    }

    public RegistrationConflictException(Field field, Throwable cause) {
        super(PUBLIC_MESSAGE, cause);
        this.field = field;
    }

    /** Server-side only: which field conflicted. Never expose to the client. */
    public Field getField() {
        return field;
    }

    /** Server-side only: USERNAME_TAKEN / EMAIL_TAKEN, for logs and tests. Never expose to the client. */
    public String getReasonCode() {
        return field.reasonCode;
    }
}
