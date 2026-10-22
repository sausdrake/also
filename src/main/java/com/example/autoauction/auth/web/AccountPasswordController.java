package com.example.autoauction.auth.web;

import com.example.autoauction.auth.application.PasswordService;
import com.example.autoauction.auth.domain.UserPrincipal;
import com.example.autoauction.auth.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AUTH-01: the signed-in user changes their own password. */
@RestController
@RequestMapping("/api/v1/account")
@Tag(name = "Учётная запись", description = "Смена пароля")
public class AccountPasswordController {

    public static final String CHANGE_PASSWORD_PATH = "/api/v1/account/password";

    private final PasswordService passwordService;

    public AccountPasswordController(PasswordService passwordService) {
        this.passwordService = passwordService;
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    /** AUTH-TG-UX-01: only whether a password exists - never anything about it. */
    public record PasswordStatus(boolean passwordSet) {}

    @GetMapping("/password")
    @Operation(summary = "Есть ли у учётной записи пароль (у входящих только через Telegram его нет)")
    public PasswordStatus passwordStatus(@Parameter(hidden = true) @CurrentUser UserPrincipal currentUser) {
        return new PasswordStatus(passwordService.hasOwnPassword(currentUser.getUserId()));
    }

    @PostMapping("/password")
    @Operation(summary = "Сменить свой пароль (после смены нужно войти заново)")
    public ResponseEntity<Void> changePassword(@RequestBody ChangePasswordRequest request,
                                               @Parameter(hidden = true) @CurrentUser UserPrincipal currentUser) {
        passwordService.changeOwnPassword(currentUser.getUserId(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
