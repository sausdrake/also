package com.example.autoauction.policy.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PolicyVersion — Slice 1b-1 domain unit tests")
class PolicyVersionTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final Long createdBy = 1L;

    @Test
    @DisplayName("valid commission is accepted, resulting policy is unpublished")
    void constructor_ValidCommission_ShouldCreateUnpublished() {
        PolicyVersion pv = new PolicyVersion(new BigDecimal("0.02"), createdBy, FIXED_CLOCK);

        assertEquals(new BigDecimal("0.02"), pv.getCommissionRate());
        assertEquals(createdBy, pv.getCreatedBy());
        assertFalse(pv.isPublished());
        assertNull(pv.getId());
        assertNull(pv.getVersionNumber());
        assertEquals(pv.getCreatedAt(), pv.getEffectiveFrom());
        assertEquals(OffsetDateTime.now(FIXED_CLOCK), pv.getCreatedAt());
    }

    @Test
    @DisplayName("zero commission is accepted")
    void constructor_ZeroCommission_ShouldBeAccepted() {
        PolicyVersion pv = new PolicyVersion(BigDecimal.ZERO, createdBy, FIXED_CLOCK);
        assertEquals(0, BigDecimal.ZERO.compareTo(pv.getCommissionRate()));
    }

    @Test
    @DisplayName("negative commission is rejected")
    void constructor_NegativeCommission_ShouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> new PolicyVersion(new BigDecimal("-0.01"), createdBy, FIXED_CLOCK));
    }

    @Test
    @DisplayName("commission exactly 1 (100%) is rejected")
    void constructor_CommissionEqualToOne_ShouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> new PolicyVersion(BigDecimal.ONE, createdBy, FIXED_CLOCK));
    }

    @Test
    @DisplayName("commission above 1 (100%) is rejected")
    void constructor_CommissionAboveOne_ShouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> new PolicyVersion(new BigDecimal("1.5"), createdBy, FIXED_CLOCK));
    }

    @Test
    @DisplayName("null commission is rejected")
    void constructor_NullCommission_ShouldThrow() {
        assertThrows(NullPointerException.class,
                () -> new PolicyVersion(null, createdBy, FIXED_CLOCK));
    }

    @Test
    @DisplayName("null createdBy is rejected")
    void constructor_NullCreatedBy_ShouldThrow() {
        assertThrows(NullPointerException.class,
                () -> new PolicyVersion(new BigDecimal("0.02"), null, FIXED_CLOCK));
    }

    @Test
    @DisplayName("publish() transitions unpublished -> published")
    void publish_WhenUnpublished_ShouldSetPublishedTrue() {
        PolicyVersion pv = new PolicyVersion(new BigDecimal("0.02"), createdBy, FIXED_CLOCK);
        pv.publish();
        assertTrue(pv.isPublished());
    }

    @Test
    @DisplayName("publish() on an already-published instance throws, never re-publishes")
    void publish_WhenAlreadyPublished_ShouldThrow() {
        PolicyVersion pv = new PolicyVersion(new BigDecimal("0.02"), createdBy, FIXED_CLOCK);
        pv.publish();
        assertThrows(IllegalStateException.class, pv::publish);
        assertTrue(pv.isPublished());
    }

    @Test
    @DisplayName("reconstruction constructor preserves every field exactly, for mapper use")
    void reconstruction_ShouldPreserveAllFields() {
        OffsetDateTime now = OffsetDateTime.now(FIXED_CLOCK);
        PolicyVersion pv = new PolicyVersion(10L, 3L, new BigDecimal("0.05"), true, now, 7L, now);

        assertEquals(10L, pv.getId());
        assertEquals(3L, pv.getVersionNumber());
        assertEquals(new BigDecimal("0.05"), pv.getCommissionRate());
        assertTrue(pv.isPublished());
        assertEquals(now, pv.getEffectiveFrom());
        assertEquals(7L, pv.getCreatedBy());
        assertEquals(now, pv.getCreatedAt());
    }
}
