package com.example.autoauction.user.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Slice 1A: User — trust gate defaults")
class UserTest {

    @Test
    @DisplayName("Конструктор без явного trust-состояния по умолчанию даёт untrusted, без метаданных верификации")
    void originalConstructor_DefaultsToUntrusted() {
        User user = new User(1L, "john", "john@test.com", "hash", true, Set.of());

        assertThat(user.isTrusted()).isFalse();
        assertThat(user.getVerifiedByAdminId()).isNull();
        assertThat(user.getVerifiedAt()).isNull();
    }

    @Test
    @DisplayName("Полный конструктор сохраняет явно переданное trust-состояние")
    void fullConstructor_PreservesExplicitTrustState() {
        OffsetDateTime verifiedAt = OffsetDateTime.parse("2026-01-01T10:00:00Z");
        User user = new User(1L, "john", "john@test.com", "hash", true, Set.of(), true, 42L, verifiedAt);

        assertThat(user.isTrusted()).isTrue();
        assertThat(user.getVerifiedByAdminId()).isEqualTo(42L);
        assertThat(user.getVerifiedAt()).isEqualTo(verifiedAt);
    }

    @Test
    @DisplayName("Untrusted-после-пересмотра отличим от никогда-не-рассмотренного - тот же trusted=false, разные метаданные")
    void untrustedAfterReview_DistinguishableFromNeverReviewed() {
        User neverReviewed = new User(1L, "a", "a@test.com", "hash", true, Set.of());
        User revoked = new User(2L, "b", "b@test.com", "hash", true, Set.of(),
                false, 42L, OffsetDateTime.now());

        assertThat(neverReviewed.isTrusted()).isFalse();
        assertThat(neverReviewed.getVerifiedByAdminId()).isNull();

        assertThat(revoked.isTrusted()).isFalse();
        assertThat(revoked.getVerifiedByAdminId()).isNotNull();
    }
}
