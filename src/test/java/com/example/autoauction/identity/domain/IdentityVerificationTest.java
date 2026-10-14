package com.example.autoauction.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4 §15 domain tests: all 5 state-transition behaviors of the current-state aggregate
 * (Phase 3/3.1). These guards protect same-row transition correctness only - the cross-account
 * uniqueness invariant is a DB-level concern this class cannot see (Phase 3.1 §1), so it is
 * deliberately not exercised here.
 */
@DisplayName("IdentityVerification - state-transition guards")
class IdentityVerificationTest {

    private final OffsetDateTime now = OffsetDateTime.now();

    private IdentityVerification pending() {
        return IdentityVerification.firstSubmission(1L, "KG", DocumentType.NATIONAL_ID, "AB1234", 10L, 10L, now);
    }

    @Test
    @DisplayName("firstSubmission() creates a PENDING_REVIEW row")
    void firstSubmission_CreatesPendingReview() {
        IdentityVerification iv = pending();
        assertThat(iv.getStatus()).isEqualTo(IdentityVerificationStatus.PENDING_REVIEW);
        assertThat(iv.getUserId()).isEqualTo(1L);
        assertThat(iv.getCanonicalDocumentNumber()).isEqualTo("AB1234");
        assertThat(iv.getCurrentDocumentId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("approve() from PENDING_REVIEW succeeds -> VERIFIED")
    void approve_FromPendingReview_Succeeds() {
        IdentityVerification iv = pending();
        iv.approve(now.plusMinutes(1));
        assertThat(iv.getStatus()).isEqualTo(IdentityVerificationStatus.VERIFIED);
    }

    @Test
    @DisplayName("approve() from any non-PENDING_REVIEW status fails closed")
    void approve_FromWrongStatus_Throws() {
        IdentityVerification iv = pending();
        iv.reject(now.plusMinutes(1));
        assertThatThrownBy(() -> iv.approve(now.plusMinutes(2))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("reject() from PENDING_REVIEW succeeds -> REJECTED")
    void reject_FromPendingReview_Succeeds() {
        IdentityVerification iv = pending();
        iv.reject(now.plusMinutes(1));
        assertThat(iv.getStatus()).isEqualTo(IdentityVerificationStatus.REJECTED);
    }

    @Test
    @DisplayName("reject() from VERIFIED fails closed")
    void reject_FromVerified_Throws() {
        IdentityVerification iv = pending();
        iv.approve(now.plusMinutes(1));
        assertThatThrownBy(() -> iv.reject(now.plusMinutes(2))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("revoke() from VERIFIED succeeds -> REVOKED")
    void revoke_FromVerified_Succeeds() {
        IdentityVerification iv = pending();
        iv.approve(now.plusMinutes(1));
        iv.revoke(now.plusMinutes(2));
        assertThat(iv.getStatus()).isEqualTo(IdentityVerificationStatus.REVOKED);
    }

    @Test
    @DisplayName("revoke() from PENDING_REVIEW fails closed (must be VERIFIED first)")
    void revoke_FromPendingReview_Throws() {
        IdentityVerification iv = pending();
        assertThatThrownBy(() -> iv.revoke(now.plusMinutes(1))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("resubmit() from REJECTED succeeds -> PENDING_REVIEW with new identity")
    void resubmit_FromRejected_Succeeds() {
        IdentityVerification iv = pending();
        iv.reject(now.plusMinutes(1));
        iv.resubmit("KG", DocumentType.INTERNATIONAL_PASSPORT, "CD5678", 20L, 20L, now.plusMinutes(2));
        assertThat(iv.getStatus()).isEqualTo(IdentityVerificationStatus.PENDING_REVIEW);
        assertThat(iv.getDocumentType()).isEqualTo(DocumentType.INTERNATIONAL_PASSPORT);
        assertThat(iv.getCanonicalDocumentNumber()).isEqualTo("CD5678");
        assertThat(iv.getCurrentDocumentId()).isEqualTo(20L);
    }

    @Test
    @DisplayName("resubmit() from REVOKED succeeds -> PENDING_REVIEW")
    void resubmit_FromRevoked_Succeeds() {
        IdentityVerification iv = pending();
        iv.approve(now.plusMinutes(1));
        iv.revoke(now.plusMinutes(2));
        iv.resubmit("KG", DocumentType.NATIONAL_ID, "EF9999", 30L, 30L, now.plusMinutes(3));
        assertThat(iv.getStatus()).isEqualTo(IdentityVerificationStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("resubmit() from PENDING_REVIEW fails closed - caller must handle this case before calling")
    void resubmit_FromPendingReview_Throws() {
        IdentityVerification iv = pending();
        assertThatThrownBy(() -> iv.resubmit("KG", DocumentType.NATIONAL_ID, "ZZ9999", 99L, 99L, now.plusMinutes(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("resubmit() from VERIFIED fails closed - replacement not supported in MVP")
    void resubmit_FromVerified_Throws() {
        IdentityVerification iv = pending();
        iv.approve(now.plusMinutes(1));
        assertThatThrownBy(() -> iv.resubmit("KG", DocumentType.NATIONAL_ID, "ZZ9999", 99L, 99L, now.plusMinutes(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("matchesIdentity() true only for an exact (country, type, canonical number) match")
    void matchesIdentity_ExactMatchOnly() {
        IdentityVerification iv = pending();
        assertThat(iv.matchesIdentity("KG", DocumentType.NATIONAL_ID, "AB1234")).isTrue();
        assertThat(iv.matchesIdentity("KZ", DocumentType.NATIONAL_ID, "AB1234")).isFalse();
        assertThat(iv.matchesIdentity("KG", DocumentType.INTERNATIONAL_PASSPORT, "AB1234")).isFalse();
        assertThat(iv.matchesIdentity("KG", DocumentType.NATIONAL_ID, "OTHER999")).isFalse();
    }

    @Test
    @DisplayName("MVP-004: a submission without a live selfie cannot be created")
    void firstSubmission_WithoutSelfie_Throws() {
        assertThatThrownBy(() -> IdentityVerification.firstSubmission(1L, "KG", DocumentType.NATIONAL_ID, "AB1234", 10L, null, now))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("MVP-004: a resubmission without a live selfie fails and changes nothing")
    void resubmit_WithoutSelfie_Throws() {
        IdentityVerification iv = pending();
        iv.reject(now.plusMinutes(1));
        assertThatThrownBy(() -> iv.resubmit("KG", DocumentType.NATIONAL_ID, "CD5678", 20L, null, now.plusMinutes(2)))
                .isInstanceOf(NullPointerException.class);
        assertThat(iv.getStatus()).isEqualTo(IdentityVerificationStatus.REJECTED);
    }

    @Test
    @DisplayName("MVP-004: resubmit() records the new selfie")
    void resubmit_RecordsNewSelfie() {
        IdentityVerification iv = pending();
        iv.reject(now.plusMinutes(1));
        iv.resubmit("KG", DocumentType.NATIONAL_ID, "CD5678", 20L, 21L, now.plusMinutes(2));
        assertThat(iv.getCurrentDocumentId()).isEqualTo(20L);
        assertThat(iv.getCurrentSelfieDocumentId()).isEqualTo(21L);
    }

    @Test
    @DisplayName("MVP-004: a pre-selfie PENDING_REVIEW row cannot be approved, but can be rejected")
    void approve_LegacyRowWithoutSelfie_FailsClosed() {
        IdentityVerification legacy = new IdentityVerification(5L, 1L, IdentityVerificationStatus.PENDING_REVIEW, "KG",
                DocumentType.NATIONAL_ID, "AB1234", 10L, null, now, now, 0L);
        assertThatThrownBy(() -> legacy.approve(now.plusMinutes(1))).isInstanceOf(IllegalStateException.class);
        assertThat(legacy.getStatus()).isEqualTo(IdentityVerificationStatus.PENDING_REVIEW);

        legacy.reject(now.plusMinutes(2));
        assertThat(legacy.getStatus()).isEqualTo(IdentityVerificationStatus.REJECTED);
    }
}
