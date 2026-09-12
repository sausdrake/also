package com.example.autoauction.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4 §15 domain tests: the per-event-type shape rules in {@code
 * IdentityVerificationHistory.validate()} - mirrored exactly by the V47 migration's
 * {@code identity_verification_history_shape_check}, so both layers must agree.
 */
@DisplayName("IdentityVerificationHistory - per-event-type shape validation")
class IdentityVerificationHistoryTest {

    private final OffsetDateTime now = OffsetDateTime.now();

    @Test
    @DisplayName("submission() requires documentReferenceId and forbids actorAdminId")
    void submission_RequiresDocumentReferenceId() {
        IdentityVerificationHistory h = IdentityVerificationHistory.submission(1L, "AB1234", 10L, now);
        assertThat(h.getDocumentReferenceId()).isEqualTo(10L);
        assertThat(h.getActorAdminId()).isNull();
    }

    @Test
    @DisplayName("approval() requires actorAdminId and forbids documentReferenceId")
    void approval_RequiresActorAdminId() {
        IdentityVerificationHistory h = IdentityVerificationHistory.approval(1L, "AB1234", 99L, now);
        assertThat(h.getActorAdminId()).isEqualTo(99L);
        assertThat(h.getDocumentReferenceId()).isNull();
    }

    @Test
    @DisplayName("rejection() requires non-blank reasonText")
    void rejection_RequiresReasonText() {
        assertThatThrownBy(() -> IdentityVerificationHistory.rejection(1L, "AB1234", 99L, null, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentityVerificationHistory.rejection(1L, "AB1234", 99L, "  ", now))
                .isInstanceOf(IllegalArgumentException.class);
        IdentityVerificationHistory h = IdentityVerificationHistory.rejection(1L, "AB1234", 99L, "поддельный документ", now);
        assertThat(h.getReasonText()).isEqualTo("поддельный документ");
    }

    @Test
    @DisplayName("revocation() requires a reasonCode")
    void revocation_RequiresReasonCode() {
        assertThatThrownBy(() -> IdentityVerificationHistory.revocation(1L, "AB1234", 99L, null, null, now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("revocation() with reasonCode=OTHER requires non-blank reasonText")
    void revocation_OtherRequiresReasonText() {
        assertThatThrownBy(() -> IdentityVerificationHistory.revocation(1L, "AB1234", 99L, RevocationReason.OTHER, null, now))
                .isInstanceOf(IllegalArgumentException.class);
        IdentityVerificationHistory h = IdentityVerificationHistory.revocation(
                1L, "AB1234", 99L, RevocationReason.OTHER, "см. тикет #42", now);
        assertThat(h.getReasonText()).isEqualTo("см. тикет #42");
    }

    @Test
    @DisplayName("revocation() with a non-OTHER reasonCode does not require reasonText")
    void revocation_NonOtherReasonCode_ReasonTextOptional() {
        IdentityVerificationHistory h = IdentityVerificationHistory.revocation(
                1L, "AB1234", 99L, RevocationReason.FRAUD_DISCOVERED, null, now);
        assertThat(h.getRevocationReasonCode()).isEqualTo(RevocationReason.FRAUD_DISCOVERED);
        assertThat(h.getReasonText()).isNull();
    }

    @Test
    @DisplayName("failedApprovalAttempt() requires actorAdminId and forbids revocationReasonCode")
    void failedApprovalAttempt_RequiresActorAdminId() {
        IdentityVerificationHistory h = IdentityVerificationHistory.failedApprovalAttempt(1L, "AB1234", 77L, now);
        assertThat(h.getActorAdminId()).isEqualTo(77L);
        assertThat(h.getEventType()).isEqualTo(IdentityEventType.FAILED_APPROVAL_ATTEMPT);
    }

    @Test
    @DisplayName("canonicalDocumentNumber is mandatory for every event type")
    void allEventTypes_RequireCanonicalDocumentNumber() {
        assertThatThrownBy(() -> IdentityVerificationHistory.approval(1L, null, 99L, now))
                .isInstanceOf(NullPointerException.class);
    }
}
