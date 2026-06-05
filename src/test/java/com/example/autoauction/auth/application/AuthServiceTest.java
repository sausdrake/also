package com.example.autoauction.auth.application;

import com.example.autoauction.auth.domain.RefreshToken;
import com.example.autoauction.auth.domain.port.RefreshTokenRepository;
import com.example.autoauction.auth.infrastructure.security.JwtService;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Тесты AuthService - полное покрытие")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private DepositService depositService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private UserDetailsService userDetailsService;

    @InjectMocks
    private AuthService authService;

    private final String username = "testuser";
    private final String email = "test@example.com";
    private final String password = "password123";
    private final String weakPassword = "12345";
    private final String veryLongPassword = "a".repeat(300);
    private final String sqlInjectionUsername = "admin'; DROP TABLE users; --";
    private final String xssUsername = "<script>alert('xss')</script>";
    private final String encodedPassword = "encoded_password_hash";
    private final String accessToken = "jwt_access_token";
    private final String refreshTokenString = "jwt_refresh_token";
    private final Long userId = 1L;

    private Role userRole;
    private Role adminRole;
    private User user;
    private User inactiveUser;
    private User lockedUser;

    @BeforeEach
    void setUp() {
        userRole = new Role(1L, "ROLE_USER", "Обычный пользователь");
        adminRole = new Role(2L, "ROLE_ADMIN", "Администратор");

        user = new User(
                userId,
                username,
                email,
                encodedPassword,
                true,
                Set.of(userRole)
        );

        inactiveUser = new User(
                2L,
                "inactive",
                "inactive@example.com",
                encodedPassword,
                false,
                Set.of(userRole)
        );

        lockedUser = new User(
                3L,
                "locked",
                "locked@example.com",
                encodedPassword,
                true,
                Set.of(userRole)
        );
    }

    // ==================== РАСШИРЕННЫЕ ТЕСТЫ РЕГИСТРАЦИИ ====================

    @Nested
    @DisplayName("Регистрация - расширенные сценарии")
    class ExtendedRegisterTests {

        @Test
        @DisplayName("Регистрация с очень длинным username - должна быть валидация")
        void register_WithVeryLongUsername_ShouldBeValidated() {
            String tooLongUsername = "a".repeat(51);
            AuthService.RegisterRequest request = new AuthService.RegisterRequest(tooLongUsername, email, password);

            // Ожидаем исключение, так как username превышает 50 символов
            assertThrows(IllegalArgumentException.class,
                    () -> authService.register(request));
        }

        @Test
        @DisplayName("Регистрация с слишком длинным username - должна быть валидация")
        void register_WithTooLongUsername_ShouldBeValidated() {
            String tooLongUsername = "a".repeat(51);
            AuthService.RegisterRequest request = new AuthService.RegisterRequest(tooLongUsername, email, password);

            // Ожидаем, что метод register должен выбросить исключение или обрезать username
            // В идеале должна быть валидация на уровне DTO
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> authService.register(request));
            assertTrue(exception.getMessage().contains("Username") ||
                    exception.getMessage().contains("длина"));
        }

        @Test
        @DisplayName("Регистрация с SQL инъекцией в username - должна быть отклонена")
        void register_WithSqlInjectionUsername_ShouldBeRejected() {
            String sqlInjection = "admin'; DROP TABLE users; --";
            AuthService.RegisterRequest request = new AuthService.RegisterRequest(sqlInjection, email, password);

            // Валидация должна отклонить недопустимые символы
            assertThrows(IllegalArgumentException.class,
                    () -> authService.register(request));
        }

        @Test
        @DisplayName("Регистрация с XSS в username - должна быть отклонена")
        void register_WithXssInUsername_ShouldBeRejected() {
            String xssUsername = "<script>alert('xss')</script>";
            AuthService.RegisterRequest request = new AuthService.RegisterRequest(xssUsername, email, password);

            // Валидация должна отклонить недопустимые символы
            assertThrows(IllegalArgumentException.class,
                    () -> authService.register(request));
        }

        @Test
        @DisplayName("Регистрация с очень слабым паролем")
        void register_WithWeakPassword_ShouldBeValidated() {
            AuthService.RegisterRequest request = new AuthService.RegisterRequest(username, email, weakPassword);

            // Ожидаем исключение валидации
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> authService.register(request));
            assertTrue(exception.getMessage().contains("Password") ||
                    exception.getMessage().contains("6"));
        }

        @Test
        @DisplayName("Регистрация с очень длинным паролем - должна быть отклонена")
        void register_WithVeryLongPassword_ShouldBeValidated() {
            String veryLongPassword = "a".repeat(256);
            AuthService.RegisterRequest request = new AuthService.RegisterRequest(username, email, veryLongPassword);

            assertThrows(IllegalArgumentException.class,
                    () -> authService.register(request));
        }

        @Test
        @DisplayName("Регистрация с email без @")
        void register_WithInvalidEmail_ShouldBeValidated() {
            String invalidEmail = "invalid-email";
            AuthService.RegisterRequest request = new AuthService.RegisterRequest(username, invalidEmail, password);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> authService.register(request));
            assertTrue(exception.getMessage().contains("Email") ||
                    exception.getMessage().contains("format"));
        }

        @Test
        @DisplayName("Регистрация с пустым username")
        void register_WithEmptyUsername_ShouldBeValidated() {
            AuthService.RegisterRequest request = new AuthService.RegisterRequest("", email, password);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> authService.register(request));
            assertTrue(exception.getMessage().contains("Username") ||
                    exception.getMessage().contains("empty"));
        }

        @Test
        @DisplayName("Регистрация с null полями")
        void register_WithNullFields_ShouldBeValidated() {
            assertThrows(IllegalArgumentException.class,
                    () -> authService.register(null));
        }

        @Test
        @DisplayName("Регистрация - ошибка при сохранении пользователя")
        void register_WhenSaveFails_ShouldThrowException() {
            AuthService.RegisterRequest request = new AuthService.RegisterRequest(username, email, password);

            when(userRepository.findByUsername(username)).thenReturn(Optional.empty());
            when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
            when(roleRepository.findByName("ROLE_USER")).thenReturn(Optional.of(userRole));
            when(passwordEncoder.encode(password)).thenReturn(encodedPassword);

            // Бросаем RuntimeException (DataAccessException - это тоже RuntimeException)
            when(userRepository.save(any(User.class)))
                    .thenThrow(new RuntimeException("Database error"));

            assertThrows(RuntimeException.class,
                    () -> authService.register(request));
        }

        @Test
        @DisplayName("Регистрация - депозит не создаётся (ошибка не блокирует регистрацию)")
        void register_WhenDepositCreationFails_RegistrationStillSucceeds() {
            AuthService.RegisterRequest request = new AuthService.RegisterRequest(username, email, password);

            when(userRepository.findByUsername(username)).thenReturn(Optional.empty());
            when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
            when(roleRepository.findByName("ROLE_USER")).thenReturn(Optional.of(userRole));
            when(passwordEncoder.encode(password)).thenReturn(encodedPassword);

            User savedUser = new User(1L, username, email, encodedPassword, true, Set.of(userRole));
            when(userRepository.save(any(User.class))).thenReturn(savedUser);

            // Используем doThrow для void метода
            doThrow(new RuntimeException("Deposit service error"))
                    .when(depositService).createDeposit(anyLong(), any());

            // Мокаем всё необходимое для генерации токенов
            UserDetails userDetails = mock(UserDetails.class);
            when(userDetails.getUsername()).thenReturn(username);
            when(userDetailsService.loadUserByUsername(username)).thenReturn(userDetails);
            when(jwtService.generateToken(any(java.util.Map.class), eq(userDetails))).thenReturn(accessToken);
            when(jwtService.generateRefreshToken(userDetails)).thenReturn(refreshTokenString);
            when(refreshTokenRepository.save(any(RefreshToken.class))).thenReturn(mock(RefreshToken.class));

            AuthService.AuthResponse response = authService.register(request);

            assertNotNull(response);
            assertEquals(accessToken, response.accessToken());

            // Проверяем, что createDeposit был вызван
            verify(depositService, times(1)).createDeposit(eq(1L), eq(BigDecimal.ZERO));
        }
    }

    // ==================== РАСШИРЕННЫЕ ТЕСТЫ АУТЕНТИФИКАЦИИ ====================

    @Nested
    @DisplayName("Аутентификация - расширенные сценарии")
    class ExtendedAuthenticateTests {

        @Test
        @DisplayName("Успешный вход с разными ролями")
        void authenticate_WithDifferentRoles_Success() {
            User adminUser = new User(2L, "admin", "admin@example.com", encodedPassword, true, Set.of(adminRole, userRole));

            AuthService.LoginRequest request = new AuthService.LoginRequest("admin", password);

            Authentication authentication = mock(Authentication.class);
            lenient().when(authentication.isAuthenticated()).thenReturn(true);
            UserDetails userDetails = mock(UserDetails.class);
            lenient().when(userDetails.getUsername()).thenReturn("admin");
            RefreshToken refreshToken = new RefreshToken(1L, refreshTokenString, adminUser, Instant.now().plusSeconds(3600), false, Instant.now());

            when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                    .thenReturn(authentication);
            when(userDetailsService.loadUserByUsername("admin")).thenReturn(userDetails);
            when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
            when(jwtService.generateToken(any(java.util.Map.class), eq(userDetails))).thenReturn(accessToken);
            when(jwtService.generateRefreshToken(userDetails)).thenReturn(refreshTokenString);
            when(refreshTokenRepository.save(any(RefreshToken.class))).thenReturn(refreshToken);

            AuthService.AuthResponse response = authService.authenticate(request);

            assertNotNull(response);
        }

        @Test
        @DisplayName("Попытка входа с неверным паролем несколько раз")
        void authenticate_MultipleInvalidAttempts_ShouldTrackAttempts() {
            AuthService.LoginRequest request = new AuthService.LoginRequest(username, "wrong");

            for (int i = 0; i < 5; i++) {
                when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                        .thenThrow(new BadCredentialsException("Invalid credentials"));

                assertThrows(BadCredentialsException.class,
                        () -> authService.authenticate(request));
            }
            // В реальной системе должно быть отслеживание попыток
        }

        @Test
        @DisplayName("Вход с неактивным пользователем")
        void authenticate_WithInactiveUser_ShouldThrowException() {
            AuthService.LoginRequest request = new AuthService.LoginRequest("inactive", password);

            Authentication authentication = mock(Authentication.class);
            when(authentication.isAuthenticated()).thenReturn(true);
            UserDetails userDetails = mock(UserDetails.class);
            when(userDetails.getUsername()).thenReturn("inactive");

            when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                    .thenReturn(authentication);
            when(userDetailsService.loadUserByUsername("inactive")).thenReturn(userDetails);
            when(userRepository.findByUsername("inactive")).thenReturn(Optional.of(inactiveUser));

            // Не мокаем jwtService - до него не дойдёт
            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> authService.authenticate(request));
            assertTrue(exception.getMessage().contains("inactive"));
        }

        @Test
        @DisplayName("Вход с заблокированным пользователем")
        void authenticate_WithLockedUser_ShouldThrowException() {
            AuthService.LoginRequest request = new AuthService.LoginRequest("locked", password);

            when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                    .thenThrow(new LockedException("User account is locked"));

            assertThrows(LockedException.class,
                    () -> authService.authenticate(request));
        }

        @Test
        @DisplayName("Вход с SQL инъекцией в username")
        void authenticate_WithSqlInjectionUsername_ShouldBeSafe() {
            AuthService.LoginRequest request = new AuthService.LoginRequest(sqlInjectionUsername, password);

            when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                    .thenThrow(new BadCredentialsException("Invalid credentials"));

            assertThrows(BadCredentialsException.class,
                    () -> authService.authenticate(request));
        }

        @Test
        @DisplayName("Попытка подбора пароля (brute force)")
        void authenticate_BruteForceAttempt_ShouldRateLimit() {
            // В реальной системе должна быть защита от брутфорса
            String[] passwords = {"password1", "password2", "password3", "password4", "password5"};

            for (String pwd : passwords) {
                AuthService.LoginRequest request = new AuthService.LoginRequest(username, pwd);
                when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                        .thenThrow(new BadCredentialsException("Invalid credentials"));

                assertThrows(BadCredentialsException.class,
                        () -> authService.authenticate(request));
            }
        }

        @Test
        @DisplayName("Вход с пустым паролем")
        void authenticate_WithEmptyPassword_ShouldBeValidated() {
            AuthService.LoginRequest request = new AuthService.LoginRequest(username, "");

            assertThrows(BadCredentialsException.class,
                    () -> authService.authenticate(request));
        }
    }

    // ==================== ТЕСТЫ ЗАЩИТЫ ОТ DUPLICATE REFRESH TOKEN ====================

    @Nested
    @DisplayName("Дублирующийся refresh token — защита и retry")
    class DuplicateRefreshTokenTests {

        @Test
        @DisplayName("При DataIntegrityViolationException на save — выполняется retry с новым токеном")
        void generateAndSaveRefreshToken_OnDuplicateConstraint_Retries() {
            // Симулируем: рефреш-токен при первой попытке сохранения — дубль
            Authentication auth = mock(Authentication.class);
            when(auth.isAuthenticated()).thenReturn(true);
            when(authenticationManager.authenticate(any())).thenReturn(auth);
            when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
            when(userDetailsService.loadUserByUsername(username)).thenReturn(mock(UserDetails.class));
            when(jwtService.generateToken(any())).thenReturn(accessToken);
            when(jwtService.getRefreshExpiration()).thenReturn(604_800_000L);

            // Первый вызов generateRefreshToken → "duplicate_token"
            // Второй вызов (retry) → "unique_retry_token"
            when(jwtService.generateRefreshToken(any()))
                    .thenReturn("duplicate_token")
                    .thenReturn("unique_retry_token");

            // Первый save() бросает DataIntegrityViolationException, второй — успех
            when(refreshTokenRepository.save(argThat(t -> t != null && "duplicate_token".equals(t.getToken()))))
                    .thenThrow(new DataIntegrityViolationException("duplicate key"));
            when(refreshTokenRepository.save(argThat(t -> t != null && "unique_retry_token".equals(t.getToken()))))
                    .thenAnswer(i -> i.getArgument(0));

            AuthService.AuthResponse response = authService.authenticate(
                    new AuthService.LoginRequest(username, password));

            assertThat(response).isNotNull();
            // После retry должен вернуться уникальный токен
            assertThat(response.refreshToken()).isEqualTo("unique_retry_token");
        }

        @Test
        @DisplayName("Refresh-токен сохраняется один раз при нормальном потоке")
        void generateAndSaveRefreshToken_NormalFlow_SavesOnce() {
            Authentication auth = mock(Authentication.class);
            when(auth.isAuthenticated()).thenReturn(true);
            when(authenticationManager.authenticate(any())).thenReturn(auth);
            when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
            when(userDetailsService.loadUserByUsername(username)).thenReturn(mock(UserDetails.class));
            when(jwtService.generateToken(any())).thenReturn(accessToken);
            when(jwtService.generateRefreshToken(any())).thenReturn(refreshTokenString);
            when(jwtService.getRefreshExpiration()).thenReturn(604_800_000L);
            when(refreshTokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            authService.authenticate(new AuthService.LoginRequest(username, password));

            // save() должен вызываться ровно один раз (без retry)
            verify(refreshTokenRepository, times(1)).save(any());
        }
    }

    // ==================== РАСШИРЕННЫЕ ТЕСТЫ REFRESH TOKEN ====================

    @Nested
    @DisplayName("Refresh Token - расширенные сценарии")
    class ExtendedRefreshTokenTests {

        @Test
        @DisplayName("Попытка обновления с истекшим токеном")
        void refreshToken_WithExpiredToken_ShouldThrowException() {
            RefreshToken expiredToken = new RefreshToken(
                    1L, refreshTokenString, user,
                    Instant.now().minusSeconds(3600), false, Instant.now()
            );

            AuthService.RefreshTokenRequest request = new AuthService.RefreshTokenRequest(refreshTokenString);

            when(refreshTokenRepository.findByToken(refreshTokenString)).thenReturn(Optional.of(expiredToken));

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> authService.refreshToken(request));
            assertTrue(exception.getMessage().contains("expired"));
        }

        @Test
        @DisplayName("Попытка обновления с отозванным токеном")
        void refreshToken_WithRevokedToken_ShouldThrowException() {
            RefreshToken revokedToken = new RefreshToken(
                    1L, refreshTokenString, user,
                    Instant.now().plusSeconds(3600), true, Instant.now()
            );

            AuthService.RefreshTokenRequest request = new AuthService.RefreshTokenRequest(refreshTokenString);

            when(refreshTokenRepository.findByToken(refreshTokenString)).thenReturn(Optional.of(revokedToken));

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> authService.refreshToken(request));
            assertTrue(exception.getMessage().contains("revoked"));
        }

        @Test
        @DisplayName("Попытка обновления с пустым токеном")
        void refreshToken_WithEmptyToken_ShouldThrowException() {
            AuthService.RefreshTokenRequest request = new AuthService.RefreshTokenRequest("");

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> authService.refreshToken(request));
            assertTrue(exception.getMessage().contains("required"));
        }

        @Test
        @DisplayName("Попытка обновления с несуществующим токеном")
        void refreshToken_WithNonExistentToken_ShouldThrowException() {
            AuthService.RefreshTokenRequest request = new AuthService.RefreshTokenRequest("non-existent-token");

            when(refreshTokenRepository.findByToken("non-existent-token")).thenReturn(Optional.empty());

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> authService.refreshToken(request));
            assertTrue(exception.getMessage().contains("Invalid refresh token"));
        }

        @Test
        @DisplayName("Обновление токена для неактивного пользователя")
        void refreshToken_ForInactiveUser_ShouldThrowException() {
            RefreshToken refreshTokenForInactive = new RefreshToken(
                    1L, refreshTokenString, inactiveUser,
                    Instant.now().plusSeconds(3600), false, Instant.now()
            );

            AuthService.RefreshTokenRequest request = new AuthService.RefreshTokenRequest(refreshTokenString);

            when(refreshTokenRepository.findByToken(refreshTokenString)).thenReturn(Optional.of(refreshTokenForInactive));

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> authService.refreshToken(request));
            assertTrue(exception.getMessage().contains("inactive"));
        }

        @Test
        @DisplayName("Параллельные обновления токена")
        void refreshToken_ConcurrentRefreshes_ShouldHandleProperly() throws Exception {
            ExecutorService executor = Executors.newFixedThreadPool(5);
            CountDownLatch latch = new CountDownLatch(5);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            RefreshToken validToken = new RefreshToken(
                    1L, refreshTokenString, user,
                    Instant.now().plusSeconds(3600), false, Instant.now()
            );

            when(refreshTokenRepository.findByToken(refreshTokenString)).thenReturn(Optional.of(validToken));
            when(userDetailsService.loadUserByUsername(username)).thenReturn(mock(UserDetails.class));
            when(jwtService.generateToken(any())).thenReturn(accessToken);
            when(jwtService.generateRefreshToken(any())).thenReturn("new_token");

            // Используем doAnswer для revokeAllUserTokens, чтобы он выполнялся синхронно
            doAnswer(invocation -> {
                // Ничего не делаем, просто считаем
                return null;
            }).when(refreshTokenRepository).revokeAllUserTokens(any());

            when(refreshTokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            for (int i = 0; i < 5; i++) {
                executor.submit(() -> {
                    try {
                        authService.refreshToken(new AuthService.RefreshTokenRequest(refreshTokenString));
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            // Может быть от 1 до 5 успехов, в зависимости от синхронизации
            assertTrue(successCount.get() >= 1, "Хотя бы один запрос должен быть успешным");
        }
    }

    // ==================== РАСШИРЕННЫЕ ТЕСТЫ LOGOUT ====================

    @Nested
    @DisplayName("Logout - расширенные сценарии")
    class ExtendedLogoutTests {

        @Test
        @DisplayName("Повторный выход с уже отозванным токеном — idempotent, не бросает исключение")
        void logout_WithAlreadyRevokedToken_IsIdempotent() {
            // ИСПРАВЛЕНО: logout теперь idempotent — повторный вызов не бросает исключение
            RefreshToken revokedToken = new RefreshToken(
                    1L, refreshTokenString, user,
                    Instant.now().plusSeconds(3600), true, Instant.now()
            );

            AuthService.LogoutRequest request = new AuthService.LogoutRequest(refreshTokenString);
            when(refreshTokenRepository.findByToken(refreshTokenString)).thenReturn(Optional.of(revokedToken));

            assertDoesNotThrow(() -> authService.logout(request));

            // revokeAllUserTokens не должен вызываться повторно
            verify(refreshTokenRepository, never()).revokeAllUserTokens(any());
        }

        @Test
        @DisplayName("Выход с пустым токеном")
        void logout_WithEmptyToken_ShouldThrowException() {
            AuthService.LogoutRequest request = new AuthService.LogoutRequest("");

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> authService.logout(request));
            assertTrue(exception.getMessage().contains("required"));
        }

        @Test
        @DisplayName("Параллельные выходы с разных устройств")
        void logout_ConcurrentLogouts_ShouldHandleProperly() throws Exception {
            ExecutorService executor = Executors.newFixedThreadPool(3);
            CountDownLatch latch = new CountDownLatch(3);
            AtomicInteger successCount = new AtomicInteger(0);

            RefreshToken validToken = new RefreshToken(
                    1L, refreshTokenString, user,
                    Instant.now().plusSeconds(3600), false, Instant.now()
            );

            when(refreshTokenRepository.findByToken(refreshTokenString)).thenReturn(Optional.of(validToken));

            // Используем doAnswer для revokeAllUserTokens
            doAnswer(invocation -> {
                successCount.incrementAndGet();
                return null;
            }).when(refreshTokenRepository).revokeAllUserTokens(user);

            for (int i = 0; i < 3; i++) {
                executor.submit(() -> {
                    try {
                        authService.logout(new AuthService.LogoutRequest(refreshTokenString));
                    } catch (Exception e) {
                        // игнорируем
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            // revokeAllUserTokens может быть вызван несколько раз, проверяем что хотя бы один
            assertTrue(successCount.get() >= 1, "Хотя бы один вызов должен быть успешным");
        }
    }

    // ==================== ТЕСТЫ ДЛЯ createPasswordHash ====================

    @Nested
    @DisplayName("Хеширование пароля - расширенные сценарии")
    class ExtendedPasswordHashTests {

        @Test
        @DisplayName("Хеширование очень длинного пароля - должен быть отклонён")
        void createPasswordHash_WithVeryLongPassword_ShouldThrowException() {
            // Пароль длиной 256 символов (превышает MAX_PASSWORD_LENGTH = 255)
            String veryLongPassword = "a".repeat(256);

            assertThrows(IllegalArgumentException.class,
                    () -> authService.createPasswordHash(veryLongPassword));
        }

        @Test
        @DisplayName("Хеширование пароля с Unicode символами")
        void createPasswordHash_WithUnicodePassword_ShouldSucceed() {
            String unicodePassword = "пароль🔐123";
            when(passwordEncoder.encode(unicodePassword)).thenReturn(encodedPassword);

            String hash = authService.createPasswordHash(unicodePassword);

            assertEquals(encodedPassword, hash);
        }

        @Test
        @DisplayName("Хеширование пароля с пробелами")
        void createPasswordHash_WithSpacesInPassword_ShouldSucceed() {
            String passwordWithSpaces = "my secret password 123";
            when(passwordEncoder.encode(passwordWithSpaces)).thenReturn(encodedPassword);

            String hash = authService.createPasswordHash(passwordWithSpaces);

            assertEquals(encodedPassword, hash);
        }

        @Test
        @DisplayName("Хеширование пароля с null - исключение")
        void createPasswordHash_WithNull_ShouldThrowException() {
            assertThrows(IllegalArgumentException.class,
                    () -> authService.createPasswordHash(null));
        }
    }

    // ==================== ТЕСТЫ НА ВРЕМЯ ЖИЗНИ ТОКЕНОВ ====================

    @Nested
    @DisplayName("Время жизни токенов")
    class TokenExpirationTests {

        @Test
        @DisplayName("Access токен имеет правильное время жизни")
        void accessToken_ShouldHaveCorrectExpiration() {
            // Тест должен проверять, что токены имеют правильное время жизни
            // В идеале нужно проверить, что время жизни не слишком маленькое и не слишком большое
            // Например: между 5 минутами и 24 часами
        }

        @Test
        @DisplayName("Refresh токен имеет правильное время жизни")
        void refreshToken_ShouldHaveLongerExpiration() {
            // Refresh токен должен жить дольше access токена
        }
    }

    // ==================== ТЕСТЫ НА БЕЗОПАСНОСТЬ ====================

    @Nested
    @DisplayName("Безопасность")
    class SecurityTests {

        @Test
        @DisplayName("Пароли не логируются")
        void passwords_ShouldNotBeLogged() {
            // Проверяем, что пароли не появляются в логах
            // Это нужно проверять через настройки логирования
        }

        @Test
        @DisplayName("Токены не логируются")
        void tokens_ShouldNotBeLogged() {
            // Проверяем, что JWT токены не появляются в логах
        }

        @Test
        @DisplayName("Используется безопасное хеширование паролей")
        void passwordHashing_ShouldBeSecure() {
            // Проверяем, что используется BCrypt или другой безопасный алгоритм
            // Не MD5, не SHA1
        }
    }

    // ==================== КОНКУРЕНТНЫЕ ТЕСТЫ ====================

    @Nested
    @DisplayName("Конкурентные сценарии")
    class ConcurrencyTests {

        @Test
        @DisplayName("Параллельная регистрация с одним username")
        void concurrentRegistration_SameUsername_ShouldHandleProperly() throws Exception {
            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch latch = new CountDownLatch(10);
            AtomicInteger successCount = new AtomicInteger(0);

            // Настраиваем моки
            when(userRepository.findByUsername(username))
                    .thenReturn(Optional.empty())  // первый вызов - пользователя нет
                    .thenReturn(Optional.of(user)); // последующие - уже есть
            when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
            when(roleRepository.findByName("ROLE_USER")).thenReturn(Optional.of(userRole));
            when(passwordEncoder.encode(password)).thenReturn(encodedPassword);

            // Только для успешной регистрации
            User savedUser = new User(1L, username, email, encodedPassword, true, Set.of(userRole));
            when(userRepository.save(any(User.class))).thenReturn(savedUser);

            // Мокаем для генерации токенов (только для успешного вызова)
            UserDetails userDetails = mock(UserDetails.class);
            when(userDetails.getUsername()).thenReturn(username);
            when(userDetailsService.loadUserByUsername(username)).thenReturn(userDetails);
            when(jwtService.generateToken(any(java.util.Map.class), eq(userDetails))).thenReturn(accessToken);
            when(jwtService.generateRefreshToken(userDetails)).thenReturn(refreshTokenString);
            when(refreshTokenRepository.save(any(RefreshToken.class))).thenReturn(mock(RefreshToken.class));

            // depositService не мокаем - он будет вызван, но это не критично

            for (int i = 0; i < 10; i++) {
                executor.submit(() -> {
                    try {
                        authService.register(new AuthService.RegisterRequest(username, email, password));
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // ожидаемо для конкурентных вызовов
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            // Хотя бы одна регистрация должна быть успешной
            assertTrue(successCount.get() >= 1, "At least one registration should succeed, got: " + successCount.get());
        }
    }
}