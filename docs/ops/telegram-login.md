# Sign-in with Telegram (AUTH-TELEGRAM-01)

AlSo accounts can be created and signed in with Telegram. Telegram is an **external identity provider**: it proves
"this is Telegram user `sub`"; the AlSo account (roles, trust, KYC, deposit) stays an AlSo account. Being signed in
with Telegram is **not** KYC and does **not** make an account trusted.

## Protocol

OpenID Connect Authorization Code flow with PKCE (S256), exactly as documented at
<https://core.telegram.org/bots/telegram-login> (discovery: `https://oauth.telegram.org/.well-known/openid-configuration`).

1. The browser asks `POST /api/auth/telegram/authorize {binding}` — `binding` is a random secret the browser keeps
   in `sessionStorage`. The backend stores the state (hashed), the binding (hashed), the PKCE verifier and a nonce,
   and returns Telegram's authorization URL.
2. Telegram redirects to the registered redirect URI (`/auth/telegram/callback` of the frontend) with `code` and
   `state` (or `error=access_denied`).
3. The page sends `POST /api/auth/telegram/callback {code, state, binding}`. The backend consumes the state once,
   exchanges the code at Telegram's token endpoint (HTTP Basic with the client secret + PKCE verifier) and validates
   the ID token: JWKS signature (RS256 / ES256), `iss = https://oauth.telegram.org`, `aud` = client id, `exp`, `iat`,
   `sub`, and the nonce when Telegram includes one.
4. The identity (`provider = TELEGRAM`, `provider_subject = sub`) resolves to its account — or a new `ROLE_USER`
   account is created (generated username, no email, no phone, no password). The response is the ordinary AlSo
   access + refresh token pair.

The browser never sees the client secret, the ID token or the Telegram id, and never chooses the account.

Token endpoint errors (AUTH-TG-ERR-01): Telegram also answers OAuth errors with HTTP 200 `{"error": ...}`.
`invalid_grant` (code used, expired or not matching the verifier) → `TELEGRAM_INVALID_CODE`, the user starts again;
any other OAuth error (`invalid_client`, …: our client or request is refused) → `TELEGRAM_REJECTED` (502); a response
with neither an error nor an `id_token` → `TELEGRAM_INVALID_TOKEN`. The log carries the HTTP status and the OAuth
error code only when it looks like a standard code (`[a-z_]{1,40}`), never `error_description`.

## Configuration (environment only — never commit values)

| Variable | Meaning |
|---|---|
| `TELEGRAM_OIDC_CLIENT_ID` | Client ID from @BotFather (the bot id) |
| `TELEGRAM_OIDC_CLIENT_SECRET` | Client Secret from @BotFather — backend only |
| `TELEGRAM_OIDC_REDIRECT_URI` | e.g. `https://also.example/auth/telegram/callback` — must be an Allowed URL in @BotFather |

Without all three, Telegram sign-in is off (`GET /api/auth/telegram/status` → `enabled: false`) and the button is
hidden; password login is unaffected. `TELEGRAM_OIDC_ISSUER`, `TELEGRAM_OIDC_AUTHORIZATION_ENDPOINT`,
`TELEGRAM_OIDC_TOKEN_ENDPOINT` and `TELEGRAM_OIDC_JWKS_URI` default to Telegram and exist only for isolated tests.

In @BotFather keep the ID-token signing algorithm at the default **RS256** (ES256 also works; EdDSA / ES256K are not
supported by this backend). No bot token is needed for sign-in.

## Who can use it

- Plain user accounts (exactly `ROLE_USER`): sign in with Telegram, or link Telegram in Profile
  (`/api/v1/account/telegram/link/*`, always the signed-in account).
- Administrator and diagnostician accounts: password login only; they cannot link Telegram (open product decisions
  P2 / P3). A linked user later given such a role can no longer sign in with Telegram.
- Password login and registration stay available (rollout: Telegram sign-in added; legacy registration not removed).

## Data

- `external_identity`: one Telegram identity per account and one account per identity (UNIQUE constraints — also
  under concurrent first logins). Display name / @username are refreshed metadata, never identifiers. A phone is
  stored only when the user opted in to the `phone` scope and Telegram marked it verified; it is never returned by
  any API.
- `oidc_authorization_request`: one row per round trip, single use, expires after 10 minutes.

## Recovery

An account created through Telegram has no password. Its Profile does not offer "Сменить пароль"
(`GET /api/v1/account/password` → `{"passwordSet": false}`, nothing else), and a password change on it is refused with
409 "нет пароля" instead of "wrong current password" (AUTH-TG-UX-01). Setting a first password is not offered: it needs
its own secure design (P4).

There is no self-service recovery when the user loses Telegram: no verified recovery factor exists yet (open product
decision P4). Interim: an administrator can set a temporary password for the account (existing admin password reset,
AUTH-01) after checking the person — the account's username is shown in its Profile.

## Not included

Bot messages (`telegram:bot_access`, TELEGRAM-NOTIFY-01), the Telegram Mini App (`initData` validation must reuse the
same identity resolver), email verification.
