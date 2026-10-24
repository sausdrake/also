package com.example.autoauction.auth.web;

import com.example.autoauction.auction.application.RateLimitException;
import com.example.autoauction.auth.application.AuthRateLimitService;
import com.example.autoauction.auth.application.AuthService;
import com.example.autoauction.legal.application.LegalAcceptanceService;
import com.example.autoauction.legal.domain.AcceptanceSource;
import com.example.autoauction.user.domain.port.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Аутентификация", description = "API для регистрации и входа в систему")
public class AuthController {

    private final AuthService authService;
    private final AuthRateLimitService authRateLimitService;
    private final LegalAcceptanceService legalAcceptanceService;
    private final UserRepository userRepository;

    public AuthController(AuthService authService, AuthRateLimitService authRateLimitService,
                          LegalAcceptanceService legalAcceptanceService, UserRepository userRepository) {
        this.authService = authService;
        this.authRateLimitService = authRateLimitService;
        this.legalAcceptanceService = legalAcceptanceService;
        this.userRepository = userRepository;
    }

    @PostMapping("/register")
    @Operation(summary = "Регистрация нового пользователя")
    public ResponseEntity<AuthService.AuthResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest
    ) {
        if (!authRateLimitService.tryAcquire(AuthRateLimitService.Scope.REGISTER, httpRequest.getRemoteAddr())) {
            throw new RateLimitException("Слишком много попыток регистрации. Попробуйте позже.");
        }
        log.info("Registration request for username: {}", request.username());
        // LEGAL-DATA-01: every required document version in force must be ticked - checked before anything is
        // written; while nothing is in force nothing is required.
        legalAcceptanceService.requireAllRequiredIn(request.acceptedDocumentVersionIds());
        AuthService.AuthResponse response = authService.register(
                new AuthService.RegisterRequest(
                        request.username(),
                        request.email(),
                        request.password()
                )
        );
        if (request.acceptedDocumentVersionIds() != null && !request.acceptedDocumentVersionIds().isEmpty()) {
            // Recorded right after the account exists. Should this fail, the account is not lost: the next sign-in
            // asks for the same acceptance (re-acceptance gate) before any protected operation.
            userRepository.findByUsername(request.username()).ifPresent(u -> legalAcceptanceService.accept(
                    u.getId(), request.acceptedDocumentVersionIds(), AcceptanceSource.REGISTRATION,
                    httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent")));
        }
        log.info("User registered successfully: {}", request.username());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    @Operation(summary = "Вход в систему")
    public ResponseEntity<AuthService.AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        if (!authRateLimitService.tryAcquire(AuthRateLimitService.Scope.LOGIN, httpRequest.getRemoteAddr())) {
            throw new RateLimitException("Слишком много попыток входа. Попробуйте позже.");
        }
        log.info("Login attempt for username: {}", request.username());
        AuthService.AuthResponse response = authService.authenticate(
                new AuthService.LoginRequest(
                        request.username(),
                        request.password()
                )
        );
        log.info("Login successful for username: {}", request.username());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Обновить access token")
    public ResponseEntity<AuthService.AuthResponse> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request
    ) {
        log.debug("Refresh token request received");
        AuthService.AuthResponse response = authService.refreshToken(
                new AuthService.RefreshTokenRequest(request.refreshToken())
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    @Operation(summary = "Выход из системы")
    public ResponseEntity<Void> logout(
            @Valid @RequestBody LogoutRequest request
    ) {
        log.info("Logout request received");
        authService.logout(new AuthService.LogoutRequest(request.refreshToken()));
        return ResponseEntity.ok().build();
    }

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 50) String username,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 6, max = 255) String password,
            /** LEGAL-DATA-01: ids of the document versions in force the user ticked (optional). */
            @Size(max = 20) java.util.List<Long> acceptedDocumentVersionIds
    ) {}

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ) {}

    public record RefreshTokenRequest(
            @NotBlank String refreshToken
    ) {}

    public record LogoutRequest(
            @NotBlank String refreshToken
    ) {}
}