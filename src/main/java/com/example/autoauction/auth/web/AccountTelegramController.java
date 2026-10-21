package com.example.autoauction.auth.web;

import com.example.autoauction.auction.application.RateLimitException;
import com.example.autoauction.auth.application.AuthRateLimitService;
import com.example.autoauction.auth.application.TelegramAuthService;
import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AUTH-TELEGRAM-01: linking Telegram to the CURRENT account. The account is always the authenticated principal -
 * no request field names a user or a Telegram id; the link round trip is bound to the user who started it.
 */
@RestController
@RequestMapping("/api/v1/account/telegram")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Привязка Telegram", description = "Привязка Telegram к текущему аккаунту")
public class AccountTelegramController {

    private final TelegramAuthService telegramAuthService;
    private final AuthRateLimitService rateLimit;

    public AccountTelegramController(TelegramAuthService telegramAuthService, AuthRateLimitService rateLimit) {
        this.telegramAuthService = telegramAuthService;
        this.rateLimit = rateLimit;
    }

    @GetMapping
    @Operation(summary = "Привязан ли Telegram к моему аккаунту")
    public TelegramAuthService.LinkStatus status(@Parameter(hidden = true) @CurrentUser UserPrincipal currentUser) {
        return telegramAuthService.status(currentUser.getUserId());
    }

    @PostMapping("/link/authorize")
    @Operation(summary = "Начать привязку Telegram к моему аккаунту")
    public TelegramAuthController.AuthorizeResponse authorize(@Valid @RequestBody TelegramAuthController.AuthorizeRequest request,
                                                             @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser,
                                                             HttpServletRequest http) {
        limit(http);
        return new TelegramAuthController.AuthorizeResponse(
                telegramAuthService.startLink(currentUser.getUserId(), request.binding()).authorizationUrl());
    }

    @PostMapping("/link/complete")
    @Operation(summary = "Завершить привязку Telegram к моему аккаунту")
    public ResponseEntity<TelegramAuthService.LinkStatus> complete(@Valid @RequestBody TelegramAuthController.CallbackRequest request,
                                                                   @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser,
                                                                   HttpServletRequest http) {
        limit(http);
        telegramAuthService.completeLink(currentUser.getUserId(), request.code(), request.state(), request.binding());
        return ResponseEntity.ok(telegramAuthService.status(currentUser.getUserId()));
    }

    private void limit(HttpServletRequest http) {
        if (!rateLimit.tryAcquire(AuthRateLimitService.Scope.TELEGRAM, http.getRemoteAddr())) {
            throw new RateLimitException("Слишком много попыток. Попробуйте позже.");
        }
    }
}
