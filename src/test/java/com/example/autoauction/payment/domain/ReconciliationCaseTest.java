package com.example.autoauction.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Slice 2: ReconciliationCase — open once, resolve once")
class ReconciliationCaseTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-10T10:00:00Z");

    @Test
    void newCase_isOpen() {
        ReconciliationCase c = new ReconciliationCase(5L, ReconciliationCaseType.MISMATCH, T0);
        assertThat(c.getStatus()).isEqualTo(ReconciliationCaseStatus.OPEN);
        assertThat(c.isOpen()).isTrue();
        assertThat(c.getPaymentId()).isEqualTo(5L);
        assertThat(c.getType()).isEqualTo(ReconciliationCaseType.MISMATCH);
        assertThat(c.getResolvedByAdminId()).isNull();
        assertThat(c.getResolvedAt()).isNull();
    }

    @Test
    void resolve_setsAllResolutionFields() {
        ReconciliationCase c = new ReconciliationCase(5L, ReconciliationCaseType.DUPLICATE, T0);
        OffsetDateTime at = T0.plusHours(1);
        c.resolve(42L, "refunded out of band", at);
        assertThat(c.getStatus()).isEqualTo(ReconciliationCaseStatus.RESOLVED);
        assertThat(c.isOpen()).isFalse();
        assertThat(c.getResolvedByAdminId()).isEqualTo(42L);
        assertThat(c.getResolutionNote()).isEqualTo("refunded out of band");
        assertThat(c.getResolvedAt()).isEqualTo(at);
    }

    @Test
    void resolve_null_admin_rejected() {
        ReconciliationCase c = new ReconciliationCase(5L, ReconciliationCaseType.STUCK, T0);
        assertThatThrownBy(() -> c.resolve(null, "x", T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void alreadyResolved_cannotResolveAgain() {
        ReconciliationCase c = new ReconciliationCase(5L, ReconciliationCaseType.LATE_AFTER_EXPIRY, T0);
        c.resolve(1L, "first", T0.plusMinutes(5));
        assertThatThrownBy(() -> c.resolve(2L, "second", T0.plusMinutes(10)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(c.getResolvedByAdminId()).isEqualTo(1L);
        assertThat(c.getResolutionNote()).isEqualTo("first");
    }
}
