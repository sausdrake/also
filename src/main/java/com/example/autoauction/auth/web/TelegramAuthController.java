package com.example.autoauction.auth.web;

import com.example.autoauction.auction.application.RateLimitException;
import com.example.autoauction.auth.application.AuthRateLimitService;
import com.example.autoauction.auth.application.AuthService;
import com.example.autoauction.auth.application.TelegramAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AUTH-TELEGRAM-01: public sign-in with Telegram (OIDC Authorization Code + PKCE). One flow for new and returning
 * users - the Telegram identity decides whether an account is created or found. The browser sends only the
 * authorization code and state Telegram returned plus its own binding secret; it never sends an ID token, a
 * Telegram id or a user id, and the response is the ordinary AlSo token pair.
 */
@RestController
@RequestMapping("/api/auth/telegram")
@Tag(name = "Вход через Telegram", description = "OIDC Authorization Code + PKCE")
public class TelegramAuthController {

    private final TelegramAuthService telegramAuthService;
    private final AuthRateLimitService rateLimit;

    public TelegramAuthController(TelegramAuthService telegramAuthService, AuthRateLimitService rateLimit) {
        this.telegramAuthService = telegramAuthService;
        this.rateLimit = rateLimit;
    }

    @GetMapping("/status")
    @Operation(summary = "Доступен ли вход через Telegram")
    public StatusResponse status() {
        return new StatusResponse(telegramAuthService.isEnabled());
    }

    @PostMapping("/authorize")
    @Operation(summary = "Начать вход через Telegram: адрес страницы авторизации Telegram")
    public AuthorizeResponse authorize(@Valid @RequestBody AuthorizeRequest request, HttpServletRequest http) {
        limit(http);
        return new AuthorizeResponse(telegramAuthService
                .startLogin(request.binding(), Boolean.TRUE.equals(request.includePhone())).authorizationUrl());
    }

    @PostMapping("/callback")
    @Operation(summary = "Завершить вход через Telegram: токены AlSo")
    public ResponseEntity<AuthService.AuthResponse> callback(@Valid @RequestBody CallbackRequest request, HttpServletRequest http) {
        limit(http);
        return ResponseEntity.ok(telegramAuthService.completeLogin(request.code(), request.state(), request.binding()));
    }

    private void limit(HttpServletRequest http) {
        if (!rateLimit.tryAcquire(AuthRateLimitService.Scope.TELEGRAM, http.getRemoteAddr())) {
            throw new RateLimitException("Слишком много попыток входа через Telegram. Попробуйте позже.");
        }
    }

    public record StatusResponse(boolean enabled) {}

    /** {@code binding}: a random secret the browser keeps (sessionStorage) and presents again at the callback. */
    public record AuthorizeRequest(@NotBlank @Size(min = 32, max = 128) String binding, Boolean includePhone) {}

    public record AuthorizeResponse(String authorizationUrl) {}

    public record CallbackRequest(
            @NotBlank @Size(max = 512) String code,
            @NotBlank @Size(max = 256) String state,
            @NotBlank @Size(min = 32, max = 128) String binding) {}
}
