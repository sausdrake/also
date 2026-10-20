package com.example.autoauction.auth.application;

import com.example.autoauction.auth.domain.RefreshToken;
import com.example.autoauction.auth.domain.port.RefreshTokenRepository;
import com.example.autoauction.auth.infrastructure.security.JwtService;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final DepositService depositService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;

    // Константы для валидации
    private static final int MIN_USERNAME_LENGTH = 3;
    private static final int MAX_USERNAME_LENGTH = 50;
    private static final int MIN_PASSWORD_LENGTH = 6;
    private static final int MAX_PASSWORD_LENGTH = 255;
    private static final int MAX_EMAIL_LENGTH = 255;
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9+_.-]+@(.+)$"
    );
    private static final Pattern USERNAME_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9_\\-\\u0400-\\u04FF]+$"
    );

    public AuthService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            RefreshTokenRepository refreshTokenRepository,
            DepositService depositService,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuthenticationManager authenticationManager,
            UserDetailsService userDetailsService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.depositService = depositService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
    }

    // ==================== РЕГИСТРАЦИЯ ====================

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Безопасное логирование - без пароля
        if (request == null) {
            log.warn("Register request is null");
            throw new IllegalArgumentException("Register request cannot be null");
        }

        log.info("Registering new user: {}", request.username());
        log.debug("Email: {}", request.email());
        // Пароль не логируем

        validateRegisterRequest(request);

        String sanitizedUsername = sanitizeInput(request.username());
        String sanitizedEmail = sanitizeInput(request.email());

        String lockKey = sanitizedUsername.intern();
        synchronized (lockKey) {
            if (userRepository.findByUsername(sanitizedUsername).isPresent()) {
                log.warn("Registration failed - username already exists: {}", sanitizedUsername);
                throw new RegistrationConflictException(RegistrationConflictException.Field.USERNAME);
            }
            if (userRepository.findByEmail(sanitizedEmail).isPresent()) {
                log.warn("Registration failed - email already exists: {}", sanitizedEmail);
                throw new RegistrationConflictException(RegistrationConflictException.Field.EMAIL);
            }

            Role userRole = roleRepository.findByName("ROLE_USER")
                    .orElseThrow(() -> {
                        log.error("Default role ROLE_USER not found in database");
                        return new RuntimeException("Default role ROLE_USER not found");
                    });

            User user = new User(
                    null,
                    sanitizedUsername,
                    sanitizedEmail,
                    passwordEncoder.encode(request.password()),
                    true,
                    Set.of(userRole)
            );

            User savedUser;
            try {
                savedUser = userRepository.save(user);
                log.info("User created successfully with ID: {}", savedUser.getId());
            } catch (DataIntegrityViolationException e) {
                // BE-AUTH-01: a concurrent registration took the username/email between the pre-check
                // above and this INSERT (the username lock is per JVM and does not cover the email).
                RegistrationConflictException.Field field = conflictingField(e);
                if (field == null) {
                    log.error("Failed to save user: {}", e.getMessage());
                    throw new RuntimeException("Failed to create user account", e);
                }
                log.warn("Registration failed - {} taken concurrently", field);
                throw new RegistrationConflictException(field, e);
            } catch (DataAccessException e) {
                log.error("Failed to save user: {}", e.getMessage());
                throw new RuntimeException("Failed to create user account", e);
            }

            try {
                depositService.createDeposit(savedUser.getId(), BigDecimal.ZERO);
                log.info("Deposit automatically created for user: {}", savedUser.getId());
            } catch (Exception e) {
                log.error("Failed to create deposit for user {}: {}", savedUser.getId(), e.getMessage());
            }

            UserDetails userDetails = userDetailsService.loadUserByUsername(savedUser.getUsername());
            String jwtToken = generateAccessToken(userDetails, savedUser);
            String refreshToken = generateAndSaveRefreshToken(savedUser);

            log.debug("Tokens generated for user: {}", savedUser.getUsername());
            return new AuthResponse(jwtToken, refreshToken);
        }
    }

    /** Which users unique constraint (V1: username/email UNIQUE) the violation names; null for any other. */
    static RegistrationConflictException.Field conflictingField(DataIntegrityViolationException e) {
        String text = String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (text.contains("users_username_key")) {
            return RegistrationConflictException.Field.USERNAME;
        }
        if (text.contains("users_email_key")) {
            return RegistrationConflictException.Field.EMAIL;
        }
        return null;
    }

    // ==================== АУТЕНТИФИКАЦИЯ ====================

    @Transactional
    public AuthResponse authenticate(LoginRequest request) {
        log.info("Authenticating user: {}", request != null ? request.username() : "null");

        // 1. Валидация входных данных
        validateLoginRequest(request);

        try {
            // 2. Аутентификация
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.username(),
                            request.password()
                    )
            );

            if (!authentication.isAuthenticated()) {
                log.warn("Authentication failed - not authenticated for user: {}", request.username());
                throw new BadCredentialsException("Authentication failed");
            }

            log.debug("Authentication successful for user: {}", request.username());

            // 3. Получение пользователя
            User user = userRepository.findByUsername(request.username())
                    .orElseThrow(() -> {
                        log.error("User not found after authentication: {}", request.username());
                        return new RuntimeException("User not found");
                    });

            // 4. Проверка активности пользователя
            if (!user.isActive()) {
                log.warn("Authentication failed - user is inactive: {}", request.username());
                // SEC-01: an authentication failure (401), not a server error
                throw new DisabledException("User account is inactive");
            }

            // 5. Генерация токенов (roles и userId включены в payload)
            UserDetails userDetails = userDetailsService.loadUserByUsername(request.username());
            String jwtToken = generateAccessToken(userDetails, user);
            String refreshToken = generateAndSaveRefreshToken(user);

            log.info("User logged in successfully: {}", request.username());
            return new AuthResponse(jwtToken, refreshToken);

        } catch (BadCredentialsException e) {
            log.warn("Authentication failed - bad credentials for user: {}", request.username());
            throw new BadCredentialsException("Invalid username or password");
        } catch (RuntimeException e) {
            log.error("Authentication error for user: {}", request.username(), e);
            throw e;
        }
    }

    // ==================== ОБНОВЛЕНИЕ ТОКЕНА ====================

    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        log.debug("Refreshing token");

        // 1. Валидация входных данных
        if (request == null || request.refreshToken() == null || request.refreshToken().trim().isEmpty()) {
            log.warn("Invalid refresh token request - token is empty");
            throw new RuntimeException("Refresh token is required");
        }

        String tokenKey = request.refreshToken().intern();

        // 2. Синхронизация для предотвращения параллельного обновления
        synchronized (tokenKey) {
            RefreshToken refreshToken = refreshTokenRepository.findByToken(request.refreshToken())
                    .orElseThrow(() -> {
                        log.warn("Invalid refresh token provided");
                        return new RuntimeException("Invalid refresh token");
                    });

            // 3. Проверка валидности токена
            if (!refreshToken.isValid()) {
                log.warn("Refresh token is expired or revoked for user: {}",
                        refreshToken.getUser().getUsername());
                throw new RuntimeException("Refresh token is expired or revoked");
            }

            User user = refreshToken.getUser();

            // 4. Проверка активности пользователя
            if (!user.isActive()) {
                log.warn("Refresh token rejected - user is inactive: {}", user.getUsername());
                // SEC-01: an authentication failure (401), not a server error
                throw new DisabledException("User account is inactive");
            }

            // 5. Генерация новых токенов (roles и userId включены в payload)
            UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
            String newJwtToken = generateAccessToken(userDetails, user);

            // 6. Ротация refresh токена
            refreshTokenRepository.revokeAllUserTokens(user);
            String newRefreshToken = generateAndSaveRefreshToken(user);

            log.info("Tokens refreshed successfully for user: {}", user.getUsername());
            return new AuthResponse(newJwtToken, newRefreshToken);
        }
    }

    // ==================== ВЫХОД ИЗ СИСТЕМЫ ====================

    @Transactional
    public void logout(LogoutRequest request) {
        log.info("Logging out user");

        if (request == null || request.refreshToken() == null || request.refreshToken().trim().isEmpty()) {
            log.warn("Invalid logout request - refresh token is empty");
            throw new RuntimeException("Refresh token is required");
        }

        String tokenKey = request.refreshToken().intern();

        synchronized (tokenKey) {
            RefreshToken refreshToken = refreshTokenRepository.findByToken(request.refreshToken())
                    .orElseThrow(() -> {
                        log.warn("Invalid refresh token provided during logout");
                        return new RuntimeException("Invalid refresh token");
                    });

            // Если токен уже отозван — логируем и выходим без ошибки (idempotent logout)
            if (refreshToken.isRevoked()) {
                log.info("Refresh token already revoked for user: {} (idempotent logout)", refreshToken.getUser().getUsername());
                return;
            }

            String username = refreshToken.getUser().getUsername();
            refreshTokenRepository.revokeAllUserTokens(refreshToken.getUser());
            log.info("User logged out successfully: {}", username);
        }
    }

    // ==================== ГЕНЕРАЦИЯ ACCESS TOKEN С CLAIMS ====================

    /** Генерирует access token с ролями и userId в payload. */
    private String generateAccessToken(UserDetails userDetails, User user) {
        List<String> roles = userDetails.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .collect(Collectors.toList());
        Map<String, Object> claims = new HashMap<>();
        claims.put("roles", roles);
        claims.put("userId", user.getId());
        claims.put("username", user.getUsername());
        claims.put("email", user.getEmail());
        return jwtService.generateToken(claims, userDetails);
    }

    // ==================== ГЕНЕРАЦИЯ REFRESH ТОКЕНА ====================

    private String generateAndSaveRefreshToken(User user) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
        String refreshTokenString = jwtService.generateRefreshToken(userDetails);

        RefreshToken refreshToken = new RefreshToken(
                null,
                refreshTokenString,
                user,
                Instant.now().plusMillis(jwtService.getRefreshExpiration()),
                false,
                Instant.now()
        );

        try {
            refreshTokenRepository.save(refreshToken);
            log.debug("Refresh token saved for user: {}", user.getUsername());
        } catch (DataIntegrityViolationException e) {
            // Крайне редкий случай: с jti это практически невозможно,
            // но на случай неожиданного дубля — повторяем с новым jti.
            log.warn("Duplicate refresh token detected for user {}, retrying...", user.getUsername());
            String retryToken = jwtService.generateRefreshToken(userDetails);
            RefreshToken retryRefreshToken = new RefreshToken(
                    null, retryToken, user,
                    Instant.now().plusMillis(jwtService.getRefreshExpiration()),
                    false, Instant.now()
            );
            refreshTokenRepository.save(retryRefreshToken);
            log.debug("Retry refresh token saved for user: {}", user.getUsername());
            return retryToken;
        }

        return refreshTokenString;
    }

    // ==================== ВАЛИДАЦИЯ ====================

    private void validateRegisterRequest(RegisterRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Register request cannot be null");
        }

        // Валидация username
        if (request.username() == null || request.username().trim().isEmpty()) {
            throw new IllegalArgumentException("Username cannot be empty");
        }

        String username = request.username().trim();
        if (username.length() < MIN_USERNAME_LENGTH) {
            throw new IllegalArgumentException("Username must be at least " + MIN_USERNAME_LENGTH + " characters");
        }

        if (username.length() > MAX_USERNAME_LENGTH) {
            throw new IllegalArgumentException("Username must be at most " + MAX_USERNAME_LENGTH + " characters");
        }

        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException("Username contains invalid characters. Allowed: letters, numbers, underscores, hyphens");
        }

        // Валидация email
        if (request.email() == null || request.email().trim().isEmpty()) {
            throw new IllegalArgumentException("Email cannot be empty");
        }

        String email = request.email().trim();
        if (email.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException("Email must be at most " + MAX_EMAIL_LENGTH + " characters");
        }

        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("Invalid email format");
        }

        // Валидация пароля
        if (request.password() == null || request.password().trim().isEmpty()) {
            throw new IllegalArgumentException("Password cannot be empty");
        }

        String password = request.password();
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }

        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at most " + MAX_PASSWORD_LENGTH + " characters");
        }
    }

    private void validateLoginRequest(LoginRequest request) {
        if (request == null) {
            throw new BadCredentialsException("Login request cannot be null");
        }

        if (request.username() == null || request.username().trim().isEmpty()) {
            throw new BadCredentialsException("Username cannot be empty");
        }

        if (request.password() == null || request.password().trim().isEmpty()) {
            throw new BadCredentialsException("Password cannot be empty");
        }

        // Дополнительная защита от слишком длинных значений
        if (request.username().length() > MAX_USERNAME_LENGTH) {
            throw new BadCredentialsException("Username is too long");
        }

        if (request.password().length() > MAX_PASSWORD_LENGTH) {
            throw new BadCredentialsException("Password is too long");
        }
    }

    // ==================== САНИТИЗАЦИЯ ====================

    private String sanitizeInput(String input) {
        if (input == null) {
            return null;
        }
        // Удаляем потенциально опасные символы (XSS, SQL injection)
        String sanitized = input.replaceAll("[<>\"'%;()&+\\\\]", "");
        // Обрезаем пробелы
        sanitized = sanitized.trim();
        // Ограничиваем длину
        if (sanitized.length() > MAX_EMAIL_LENGTH) {
            sanitized = sanitized.substring(0, MAX_EMAIL_LENGTH);
        }
        return sanitized;
    }

    // ==================== ВНУТРЕННИЕ КЛАССЫ (RECORDS) ====================

    public record RegisterRequest(String username, String email, String password) {}
    public record LoginRequest(String username, String password) {}
    public record RefreshTokenRequest(String refreshToken) {}
    public record LogoutRequest(String refreshToken) {}
    public record AuthResponse(String accessToken, String refreshToken) {}
}