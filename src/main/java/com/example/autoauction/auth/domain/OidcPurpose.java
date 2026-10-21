package com.example.autoauction.auth.domain;

/** AUTH-TELEGRAM-01: what an OIDC authorization round trip was started for. */
public enum OidcPurpose {
    /** Sign in (or create the account for an unknown identity). */
    LOGIN,
    /** Attach the identity to the already authenticated account that started the round trip. */
    LINK
}
