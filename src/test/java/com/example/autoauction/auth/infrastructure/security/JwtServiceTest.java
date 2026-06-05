package com.example.autoauction.auth.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtService — генерация и валидация токенов")
class JwtServiceTest {

    private static final String SECRET = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";

    private JwtService jwtService;
    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKey", SECRET);
        ReflectionTestUtils.setField(jwtService, "jwtExpiration", 86_400_000L);
        ReflectionTestUtils.setField(jwtService, "refreshExpiration", 604_800_000L);
        userDetails = new User("testuser", "password", Collections.emptyList());
    }

    @Nested
    @DisplayName("Уникальность токенов (защита от дублирования в БД)")
    class TokenUniquenessTests {

        @Test
        @DisplayName("100 access-токенов для одного пользователя должны быть уникальными")
        void generateToken_Hundred_AllUnique() {
            Set<String> tokens = new HashSet<>();
            for (int i = 0; i < 100; i++) {
                tokens.add(jwtService.generateToken(userDetails));
            }
            assertThat(tokens).hasSize(100);
        }

        @Test
        @DisplayName("100 refresh-токенов для одного пользователя должны быть уникальными")
        void generateRefreshToken_Hundred_AllUnique() {
            Set<String> tokens = new HashSet<>();
            for (int i = 0; i < 100; i++) {
                tokens.add(jwtService.generateRefreshToken(userDetails));
            }
            assertThat(tokens).hasSize(100);
        }

        @Test
        @DisplayName("Токены одного пользователя разных типов не пересекаются")
        void accessAndRefreshTokens_AreDistinct() {
            String access = jwtService.generateToken(userDetails);
            String refresh = jwtService.generateRefreshToken(userDetails);
            assertThat(access).isNotEqualTo(refresh);
        }

        @Test
        @DisplayName("Два токена в одну и ту же миллисекунду не совпадают (jti защита)")
        void twoTokensAtSameMillisecond_AreDistinct() throws Exception {
            // Симулируем одновременную генерацию в одном потоке
            String t1 = jwtService.generateToken(userDetails);
            String t2 = jwtService.generateToken(userDetails);
            assertThat(t1).isNotEqualTo(t2);
        }

        @Test
        @DisplayName("Параллельная генерация 50 токенов — все уникальные")
        void parallelGeneration_AllUnique() throws Exception {
            Set<String> tokens = java.util.Collections.synchronizedSet(new HashSet<>());
            var executor = java.util.concurrent.Executors.newFixedThreadPool(10);
            var latch = new java.util.concurrent.CountDownLatch(50);

            for (int i = 0; i < 50; i++) {
                executor.submit(() -> {
                    try {
                        tokens.add(jwtService.generateRefreshToken(userDetails));
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(tokens).hasSize(50);
        }
    }

    @Nested
    @DisplayName("Базовая корректность токенов")
    class TokenValidityTests {

        @Test
        @DisplayName("Сгенерированный токен содержит правильный username")
        void generateToken_ContainsCorrectUsername() {
            String token = jwtService.generateToken(userDetails);
            assertThat(jwtService.extractUsername(token)).isEqualTo("testuser");
        }

        @Test
        @DisplayName("Токен валиден для правильного пользователя")
        void isTokenValid_WithCorrectUser_ReturnsTrue() {
            String token = jwtService.generateToken(userDetails);
            assertThat(jwtService.isTokenValid(token, userDetails)).isTrue();
        }

        @Test
        @DisplayName("Токен невалиден для другого пользователя")
        void isTokenValid_WithDifferentUser_ReturnsFalse() {
            String token = jwtService.generateToken(userDetails);
            UserDetails otherUser = new User("other", "pass", Collections.emptyList());
            assertThat(jwtService.isTokenValid(token, otherUser)).isFalse();
        }

        @Test
        @DisplayName("Refresh-токен содержит правильный username")
        void generateRefreshToken_ContainsCorrectUsername() {
            String token = jwtService.generateRefreshToken(userDetails);
            assertThat(jwtService.extractUsername(token)).isEqualTo("testuser");
        }
    }
}
