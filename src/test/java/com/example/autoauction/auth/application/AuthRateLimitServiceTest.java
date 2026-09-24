package com.example.autoauction.auth.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuthRateLimitService — IP-ключевое ограничение auth-эндпоинтов")
class AuthRateLimitServiceTest {

    private AuthRateLimitService service;

    @BeforeEach
    void setUp() {
        service = new AuthRateLimitService();
    }

    @Nested
    @DisplayName("Базовое ограничение по IP и scope")
    class RateLimitEnforcement {

        @Test
        @DisplayName("Первые LOGIN_PER_MINUTE запросов одного IP — разрешены")
        void firstRequestsWithinLimit_Allowed() {
            String ip = "10.0.0.1";
            for (int i = 0; i < AuthRateLimitService.LOGIN_PER_MINUTE; i++) {
                assertThat(service.tryAcquire(AuthRateLimitService.Scope.LOGIN, ip))
                        .as("Запрос %d должен быть разрешён", i + 1)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("Запрос сверх лимита — отклонён")
        void requestBeyondLimit_Rejected() {
            String ip = "10.0.0.2";
            for (int i = 0; i < AuthRateLimitService.LOGIN_PER_MINUTE; i++) {
                service.tryAcquire(AuthRateLimitService.Scope.LOGIN, ip);
            }
            assertThat(service.tryAcquire(AuthRateLimitService.Scope.LOGIN, ip)).isFalse();
        }

        @Test
        @DisplayName("Разные IP имеют независимые лимиты")
        void differentIps_HaveIndependentLimits() {
            String ip1 = "10.0.0.10";
            String ip2 = "10.0.0.20";

            for (int i = 0; i < AuthRateLimitService.LOGIN_PER_MINUTE; i++) {
                service.tryAcquire(AuthRateLimitService.Scope.LOGIN, ip1);
            }
            assertThat(service.tryAcquire(AuthRateLimitService.Scope.LOGIN, ip1)).isFalse();
            assertThat(service.tryAcquire(AuthRateLimitService.Scope.LOGIN, ip2)).isTrue();
        }

        @Test
        @DisplayName("Разные scope одного IP независимы (LOGIN не влияет на REGISTER/CREATE_HASH)")
        void differentScopes_SameIp_HaveIndependentLimits() {
            String ip = "10.0.0.30";
            for (int i = 0; i < AuthRateLimitService.LOGIN_PER_MINUTE; i++) {
                service.tryAcquire(AuthRateLimitService.Scope.LOGIN, ip);
            }
            assertThat(service.tryAcquire(AuthRateLimitService.Scope.LOGIN, ip)).isFalse();

            assertThat(service.tryAcquire(AuthRateLimitService.Scope.REGISTER, ip)).isTrue();
            assertThat(service.tryAcquire(AuthRateLimitService.Scope.CREATE_HASH, ip)).isTrue();
        }

        @Test
        @DisplayName("REGISTER имеет собственный (меньший) лимит, отдельный от LOGIN/CREATE_HASH")
        void registerScope_HasOwnCapacity() {
            String ip = "10.0.0.31";
            for (int i = 0; i < AuthRateLimitService.REGISTER_PER_MINUTE; i++) {
                assertThat(service.tryAcquire(AuthRateLimitService.Scope.REGISTER, ip)).isTrue();
            }
            assertThat(service.tryAcquire(AuthRateLimitService.Scope.REGISTER, ip)).isFalse();
        }

        @Test
        @DisplayName("После resetLimit лимит восстанавливается")
        void resetLimit_RestoresLimit() {
            String ip = "10.0.0.40";
            for (int i = 0; i < AuthRateLimitService.REGISTER_PER_MINUTE; i++) {
                service.tryAcquire(AuthRateLimitService.Scope.REGISTER, ip);
            }
            assertThat(service.tryAcquire(AuthRateLimitService.Scope.REGISTER, ip)).isFalse();

            service.resetLimit(AuthRateLimitService.Scope.REGISTER, ip);

            assertThat(service.tryAcquire(AuthRateLimitService.Scope.REGISTER, ip)).isTrue();
        }
    }

    @Nested
    @DisplayName("Управление памятью — защита от утечки")
    class MemoryManagement {

        @Test
        @DisplayName("bucketCount() отражает количество активных ключей (scope+IP)")
        void bucketCount_ReflectsActiveKeys() {
            service.tryAcquire(AuthRateLimitService.Scope.LOGIN, "10.0.1.1");
            service.tryAcquire(AuthRateLimitService.Scope.REGISTER, "10.0.1.1");
            service.tryAcquire(AuthRateLimitService.Scope.CREATE_HASH, "10.0.1.2");

            assertThat(service.bucketCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("evictStaleBuckets() удаляет бакеты старше 15 минут")
        void evictStaleBuckets_RemovesOldEntries() throws Exception {
            service.tryAcquire(AuthRateLimitService.Scope.LOGIN, "10.0.2.1");
            service.tryAcquire(AuthRateLimitService.Scope.LOGIN, "10.0.2.2");

            assertThat(service.bucketCount()).isEqualTo(2);

            setLastUsed(AuthRateLimitService.Scope.LOGIN, "10.0.2.2", Instant.now().minusSeconds(16 * 60));

            service.evictStaleBuckets();

            assertThat(service.bucketCount()).isEqualTo(1);
            assertThat(service.tryAcquire(AuthRateLimitService.Scope.LOGIN, "10.0.2.1")).isTrue();
        }

        @Test
        @DisplayName("evictStaleBuckets() не удаляет свежие бакеты")
        void evictStaleBuckets_KeepsFreshEntries() {
            service.tryAcquire(AuthRateLimitService.Scope.LOGIN, "10.0.3.1");
            service.tryAcquire(AuthRateLimitService.Scope.REGISTER, "10.0.3.1");

            service.evictStaleBuckets();

            assertThat(service.bucketCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("resetLimit() уменьшает количество бакетов")
        void resetLimit_DecreasesBucketCount() {
            service.tryAcquire(AuthRateLimitService.Scope.LOGIN, "10.0.4.1");
            service.tryAcquire(AuthRateLimitService.Scope.LOGIN, "10.0.4.2");
            assertThat(service.bucketCount()).isEqualTo(2);

            service.resetLimit(AuthRateLimitService.Scope.LOGIN, "10.0.4.1");
            assertThat(service.bucketCount()).isEqualTo(1);
        }

        /** Через рефлексию задаём lastUsed для конкретного ключа (scope+IP). */
        @SuppressWarnings("unchecked")
        private void setLastUsed(AuthRateLimitService.Scope scope, String ip, Instant time) throws Exception {
            Field bucketsField = AuthRateLimitService.class.getDeclaredField("buckets");
            bucketsField.setAccessible(true);
            Map<String, AuthRateLimitService.BucketEntry> buckets =
                    (Map<String, AuthRateLimitService.BucketEntry>) bucketsField.get(service);
            AuthRateLimitService.BucketEntry entry = buckets.get(scope.name() + ":" + ip);
            if (entry != null) {
                Field lastUsedField = AuthRateLimitService.BucketEntry.class.getDeclaredField("lastUsed");
                lastUsedField.setAccessible(true);
                lastUsedField.set(entry, time);
            }
        }
    }
}
