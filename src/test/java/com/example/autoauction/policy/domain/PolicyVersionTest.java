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
        PolicyVersion pv = new PolicyVersion(new BigDecimal("0.02"), createdBy, null, FIXED_CLOCK);

        assertEquals(new BigDecimal("0.02"), pv.getCommissionRate());
        assertEquals(createdBy, pv.getCreatedBy());
        assertFalse(pv.isPublished());
        assertNull(pv.getId());
        assertNull(pv.getVersionNumber());
        assertEquals(OffsetDateTime.now(FIXED_CLOCK), pv.getCreatedAt());
        assertNull(pv.getPublishedBy());
        assertNull(pv.getPublishedAt());
        assertNull(pv.getBasedOnPublishedVersionId());
        assertEquals(0L, pv.getVersion());
        assertEquals(0L, pv.getConfigurationRevision());
    }

    @Test
    @DisplayName("basedOnPublishedVersionId is captured at construction and exposed unchanged")
    void constructor_BasedOnPublishedVersionId_IsCaptured() {
        PolicyVersion pv = new PolicyVersion(new BigDecimal("0.02"), createdBy, 7L, FIXED_CLOCK);
        assertEquals(7L, pv.getBasedOnPublishedVersionId());
    }

    @Test
    @DisplayName("zero commission is accepted")
    void constructor_ZeroCommission_ShouldBeAccepted() {
        PolicyVersion pv = new PolicyVersion(BigDecimal.ZERO, createdBy, null, FIXED_CLOCK);
        assertEquals(0, BigDecimal.ZERO.compareTo(pv.getCommissionRate()));
    }

    @Test
    @DisplayName("negative commission is rejected")
    void constructor_NegativeCommission_ShouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> new PolicyVersion(new BigDecimal("-0.01"), createdBy, null, FIXED_CLOCK));
    }

    @Test
    @DisplayName("commission exactly 1 (100%) is rejected")
    void constructor_CommissionEqualToOne_ShouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> new PolicyVersion(BigDecimal.ONE, createdBy, null, FIXED_CLOCK));
    }

    @Test
    @DisplayName("commission above 1 (100%) is rejected")
    void constructor_CommissionAboveOne_ShouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> new PolicyVersion(new BigDecimal("1.5"), createdBy, null, FIXED_CLOCK));
    }

    @Test
    @DisplayName("null commission is rejected")
    void constructor_NullCommission_ShouldThrow() {
        assertThrows(NullPointerException.class,
                () -> new PolicyVersion(null, createdBy, null, FIXED_CLOCK));
    }

    @Test
    @DisplayName("null createdBy is rejected")
    void constructor_NullCreatedBy_ShouldThrow() {
        assertThrows(NullPointerException.class,
                () -> new PolicyVersion(new BigDecimal("0.02"), null, null, FIXED_CLOCK));
    }

    @Test
    @DisplayName("publish() transitions unpublished -> published and sets publication metadata")
    void publish_WhenUnpublished_ShouldSetPublishedTrue() {
        PolicyVersion pv = new PolicyVersion(new BigDecimal("0.02"), createdBy, null, FIXED_CLOCK);
        OffsetDateTime publishedAt = OffsetDateTime.now(FIXED_CLOCK);
        pv.publish(99L, publishedAt);
        assertTrue(pv.isPublished());
        assertEquals(99L, pv.getPublishedBy());
        assertEquals(publishedAt, pv.getPublishedAt());
    }

    @Test
    @DisplayName("publish() on an already-published instance throws, never re-publishes")
    void publish_WhenAlreadyPublished_ShouldThrow() {
        PolicyVersion pv = new PolicyVersion(new BigDecimal("0.02"), createdBy, null, FIXED_CLOCK);
        pv.publish(99L, OffsetDateTime.now(FIXED_CLOCK));
        assertThrows(IllegalStateException.class, () -> pv.publish(99L, OffsetDateTime.now(FIXED_CLOCK)));
        assertTrue(pv.isPublished());
    }

    // ── Slice 1 (Policy Administration): changeCommissionRate ────────────────────

    @Test
    @DisplayName("changeCommissionRate on an unpublished draft updates the field")
    void changeCommissionRate_WhenDraft_ShouldUpdate() {
        PolicyVersion pv = new PolicyVersion(new BigDecimal("0.02"), createdBy, null, FIXED_CLOCK);
        pv.changeCommissionRate(new BigDecimal("0.0250"));
        assertEquals(0, new BigDecimal("0.0250").compareTo(pv.getCommissionRate()));
        assertFalse(pv.isPublished());
    }

    @Test
    @DisplayName("changeCommissionRate on a published version is rejected (immutable)")
    void changeCommissionRate_WhenPublished_ShouldThrow() {
        PolicyVersion pv = new PolicyVersion(new BigDecimal("0.02"), createdBy, null, FIXED_CLOCK);
        pv.publish(99L, OffsetDateTime.now(FIXED_CLOCK));
        assertThrows(IllegalStateException.class, () -> pv.changeCommissionRate(new BigDecimal("0.03")));
        assertEquals(0, new BigDecimal("0.02").compareTo(pv.getCommissionRate()));
    }

    @Test
    @DisplayName("changeCommissionRate rejects null")
    void changeCommissionRate_Null_ShouldThrow() {
        PolicyVersion pv = new PolicyVersion(new BigDecimal("0.02"), createdBy, null, FIXED_CLOCK);
        assertThrows(NullPointerException.class, () -> pv.changeCommissionRate(null));
    }

    @Test
    @DisplayName("changeCommissionRate rejects a negative rate")
    void changeCommissionRate_Negative_ShouldThrow() {
        PolicyVersion pv = new PolicyVersion(new BigDecimal("0.02"), createdBy, null, FIXED_CLOCK);
        assertThrows(IllegalArgumentException.class, () -> pv.changeCommissionRate(new BigDecimal("-0.01")));
    }

    @Test
    @DisplayName("changeCommissionRate rejects a rate >= 1 (100%)")
    void changeCommissionRate_AtOrAboveOne_ShouldThrow() {
        PolicyVersion pv = new PolicyVersion(new BigDecimal("0.02"), createdBy, null, FIXED_CLOCK);
        assertThrows(IllegalArgumentException.class, () -> pv.changeCommissionRate(BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> pv.changeCommissionRate(new BigDecimal("1.5")));
    }

    @Test
    @DisplayName("SYSTEM_ACTOR_ID is the reserved bootstrap creating actor 0; " +
            "SYSTEM_BOOTSTRAP_PUBLISHER_ID is a distinct reserved bootstrap publishing actor")
    void systemActorIds_AreReservedAndDistinct() {
        assertEquals(0L, PolicyVersion.SYSTEM_ACTOR_ID);
        assertEquals(-1L, PolicyVersion.SYSTEM_BOOTSTRAP_PUBLISHER_ID);
        assertNotEquals(PolicyVersion.SYSTEM_ACTOR_ID, PolicyVersion.SYSTEM_BOOTSTRAP_PUBLISHER_ID);
    }

    @Test
    @DisplayName("reconstruction constructor preserves every field exactly, for mapper use")
    void reconstruction_ShouldPreserveAllFields() {
        OffsetDateTime now = OffsetDateTime.now(FIXED_CLOCK);
        PolicyVersion pv = new PolicyVersion(10L, 3L, new BigDecimal("0.05"), true,
                7L, now, 8L, now, 2L, 5L, 9L);

        assertEquals(10L, pv.getId());
        assertEquals(3L, pv.getVersionNumber());
        assertEquals(new BigDecimal("0.05"), pv.getCommissionRate());
        assertTrue(pv.isPublished());
        assertEquals(7L, pv.getCreatedBy());
        assertEquals(now, pv.getCreatedAt());
        assertEquals(8L, pv.getPublishedBy());
        assertEquals(now, pv.getPublishedAt());
        assertEquals(2L, pv.getBasedOnPublishedVersionId());
        assertEquals(5L, pv.getVersion());
        assertEquals(9L, pv.getConfigurationRevision());
    }
}
