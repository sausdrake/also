package com.example.autoauction.auction.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RateLimitService — ограничение ставок и управление памятью")
class RateLimitServiceTest {

    private RateLimitService service;

    @BeforeEach
    void setUp() {
        service = new RateLimitService();
    }

    @Nested
    @DisplayName("Базовое ограничение ставок")
    class RateLimitEnforcement {

        @Test
        @DisplayName("Первые 10 запросов одного пользователя — разрешены")
        void firstTenRequests_Allowed() {
            Long userId = 1L;
            for (int i = 0; i < RateLimitService.BIDS_PER_MINUTE; i++) {
                assertThat(service.tryAcquire(userId))
                        .as("Запрос %d должен быть разрешён", i + 1)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("11-й запрос сверх лимита — отклонён")
        void eleventhRequest_Rejected() {
            Long userId = 2L;
            for (int i = 0; i < RateLimitService.BIDS_PER_MINUTE; i++) {
                service.tryAcquire(userId);
            }
            assertThat(service.tryAcquire(userId)).isFalse();
        }

        @Test
        @DisplayName("Разные пользователи имеют независимые лимиты")
        void differentUsers_HaveIndependentLimits() {
            Long user1 = 10L;
            Long user2 = 20L;

            // Исчерпываем лимит user1
            for (int i = 0; i < RateLimitService.BIDS_PER_MINUTE; i++) {
                service.tryAcquire(user1);
            }
            assertThat(service.tryAcquire(user1)).isFalse();

            // user2 не затронут
            assertThat(service.tryAcquire(user2)).isTrue();
        }

        @Test
        @DisplayName("После resetUserLimit лимит восстанавливается")
        void resetUserLimit_RestoresLimit() {
            Long userId = 3L;
            for (int i = 0; i < RateLimitService.BIDS_PER_MINUTE; i++) {
                service.tryAcquire(userId);
            }
            assertThat(service.tryAcquire(userId)).isFalse();

            service.resetUserLimit(userId);

            assertThat(service.tryAcquire(userId)).isTrue();
        }
    }

    @Nested
    @DisplayName("Управление памятью — защита от утечки")
    class MemoryManagement {

        @Test
        @DisplayName("bucketCount() отражает количество активных пользователей")
        void bucketCount_ReflectsActiveUsers() {
            service.tryAcquire(100L);
            service.tryAcquire(200L);
            service.tryAcquire(300L);

            assertThat(service.bucketCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("evictStaleBuckets() удаляет бакеты старше 15 минут")
        void evictStaleBuckets_RemovesOldEntries() throws Exception {
            Long activeUser = 1L;
            Long staleUser = 2L;

            service.tryAcquire(activeUser);
            service.tryAcquire(staleUser);

            assertThat(service.bucketCount()).isEqualTo(2);

            // Вручную делаем bake staleUser устаревшим
            setLastUsed(staleUser, Instant.now().minusSeconds(16 * 60));

            service.evictStaleBuckets();

            assertThat(service.bucketCount()).isEqualTo(1);
            // activeUser должен остаться
            assertThat(service.tryAcquire(activeUser)).isTrue();
        }

        @Test
        @DisplayName("evictStaleBuckets() не удаляет свежие бакеты")
        void evictStaleBuckets_KeepsFreshEntries() {
            service.tryAcquire(1L);
            service.tryAcquire(2L);
            service.tryAcquire(3L);

            service.evictStaleBuckets();

            assertThat(service.bucketCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("resetUserLimit() уменьшает количество бакетов")
        void resetUserLimit_DecreasesBucketCount() {
            service.tryAcquire(1L);
            service.tryAcquire(2L);
            assertThat(service.bucketCount()).isEqualTo(2);

            service.resetUserLimit(1L);
            assertThat(service.bucketCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("100 разных пользователей — не создают бесконечный рост (evict работает)")
        void manyUsers_EvictionPreventsGrowth() throws Exception {
            for (long i = 1; i <= 100; i++) {
                service.tryAcquire(i);
            }
            assertThat(service.bucketCount()).isEqualTo(100);

            // Помечаем всех как устаревших
            for (long i = 1; i <= 100; i++) {
                setLastUsed(i, Instant.now().minusSeconds(16 * 60));
            }

            service.evictStaleBuckets();
            assertThat(service.bucketCount()).isEqualTo(0);
        }

        /** Через рефлексию задаём lastUsed для конкретного пользователя. */
        @SuppressWarnings("unchecked")
        private void setLastUsed(Long userId, Instant time) throws Exception {
            Field field = RateLimitService.class.getDeclaredField("userBuckets");
            field.setAccessible(true);
            Map<Long, RateLimitService.BucketEntry> buckets =
                    (Map<Long, RateLimitService.BucketEntry>) field.get(service);
            RateLimitService.BucketEntry entry = buckets.get(userId);
            if (entry != null) {
                Field lastUsedField = RateLimitService.BucketEntry.class.getDeclaredField("lastUsed");
                lastUsedField.setAccessible(true);
                lastUsedField.set(entry, time);
            }
        }
    }
}
