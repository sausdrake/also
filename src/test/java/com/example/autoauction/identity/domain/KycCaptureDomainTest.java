package com.example.autoauction.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** KYC-CAPTURE-01: the document-side rule, the capture slot and the aggregate's back-side invariant. */
class KycCaptureDomainTest {

    private static final OffsetDateTime T = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    // ---- DocumentCapturePolicy ----

    @Test
    @DisplayName("ID card and driver licence: FRONT then BACK; passport: DATA_PAGE only")
    void requiredSides() {
        assertThat(DocumentCapturePolicy.requiredSides(DocumentType.NATIONAL_ID))
                .containsExactly(IdentityCaptureSide.FRONT, IdentityCaptureSide.BACK);
        assertThat(DocumentCapturePolicy.requiredSides(DocumentType.DRIVER_LICENSE))
                .containsExactly(IdentityCaptureSide.FRONT, IdentityCaptureSide.BACK);
        assertThat(DocumentCapturePolicy.requiredSides(DocumentType.INTERNATIONAL_PASSPORT))
                .containsExactly(IdentityCaptureSide.DATA_PAGE);
        assertThat(DocumentCapturePolicy.requiresBack(DocumentType.NATIONAL_ID)).isTrue();
        assertThat(DocumentCapturePolicy.requiresBack(DocumentType.DRIVER_LICENSE)).isTrue();
        assertThat(DocumentCapturePolicy.requiresBack(DocumentType.INTERNATIONAL_PASSPORT)).isFalse();
        assertThat(DocumentCapturePolicy.mainSide(DocumentType.NATIONAL_ID)).isEqualTo(IdentityCaptureSide.FRONT);
        assertThat(DocumentCapturePolicy.mainSide(DocumentType.INTERNATIONAL_PASSPORT)).isEqualTo(IdentityCaptureSide.DATA_PAGE);
    }

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @CsvSource({
            "NATIONAL_ID, FRONT, true", "NATIONAL_ID, BACK, true", "NATIONAL_ID, DATA_PAGE, false", "NATIONAL_ID, SELFIE, false",
            "DRIVER_LICENSE, FRONT, true", "DRIVER_LICENSE, BACK, true", "DRIVER_LICENSE, DATA_PAGE, false",
            "INTERNATIONAL_PASSPORT, DATA_PAGE, true", "INTERNATIONAL_PASSPORT, FRONT, false", "INTERNATIONAL_PASSPORT, BACK, false"
    })
    void allows(DocumentType type, IdentityCaptureSide side, boolean expected) {
        assertThat(DocumentCapturePolicy.allows(type, side)).isEqualTo(expected);
    }

    // ---- IdentityDocument.captureSlot ----

    @Test
    @DisplayName("a capture slot records its side and document type, unconsumed")
    void captureSlotRecordsPurpose() {
        IdentityDocument slot = IdentityDocument.captureSlot("k", 1L, IdentityCaptureSide.BACK, DocumentType.DRIVER_LICENSE, "image/jpeg", T);
        assertThat(slot.getCaptureSide()).isEqualTo(IdentityCaptureSide.BACK);
        assertThat(slot.getDocumentType()).isEqualTo(DocumentType.DRIVER_LICENSE);
        assertThat(slot.isConsumed()).isFalse();
        IdentityDocument selfie = IdentityDocument.captureSlot("s", 1L, IdentityCaptureSide.SELFIE, null, "image/jpeg", T);
        assertThat(selfie.getDocumentType()).isNull();
    }

    @ParameterizedTest(name = "{0} / {1} -> refused")
    @CsvSource({
            "BACK, INTERNATIONAL_PASSPORT", "FRONT, INTERNATIONAL_PASSPORT", "DATA_PAGE, NATIONAL_ID",
            "DATA_PAGE, DRIVER_LICENSE", "SELFIE, NATIONAL_ID", "FRONT, ", "BACK, "
    })
    void captureSlotRefusesSideOutsideTheRule(IdentityCaptureSide side, DocumentType type) {
        assertThatThrownBy(() -> IdentityDocument.captureSlot("k", 1L, side, type, "image/jpeg", T))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void captureSlotNeedsASide() {
        assertThatThrownBy(() -> IdentityDocument.captureSlot("k", 1L, null, DocumentType.NATIONAL_ID, "image/jpeg", T))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("validity window: valid up to and including createdAt + validity, expired one nanosecond later")
    void expiryBoundary() {
        IdentityDocument slot = IdentityDocument.captureSlot("k", 1L, IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, "image/jpeg", T);
        Duration validity = Duration.ofMinutes(15);
        assertThat(slot.isExpiredAt(T, validity)).isFalse();
        assertThat(slot.isExpiredAt(T.plusMinutes(15), validity)).isFalse();
        assertThat(slot.isExpiredAt(T.plusMinutes(15).plusNanos(1), validity)).isTrue();
    }

    @Test
    void consumedFlag() {
        IdentityDocument used = new IdentityDocument(1L, "k", 1L, "image/jpeg", 3L, T, IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, T);
        assertThat(used.isConsumed()).isTrue();
    }

    // ---- IdentityVerification back-side invariant ----

    @Test
    @DisplayName("ID card / driver licence submissions need the back; a passport refuses one")
    void submissionBackSideShape() {
        assertThatThrownBy(() -> IdentityVerification.firstSubmission(1L, "KG", DocumentType.NATIONAL_ID, "AB1234", 10L, null, 11L, T))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentityVerification.firstSubmission(1L, "KG", DocumentType.DRIVER_LICENSE, "AB1234", 10L, null, 11L, T))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentityVerification.firstSubmission(1L, "KG", DocumentType.INTERNATIONAL_PASSPORT, "AB1234", 10L, 12L, 11L, T))
                .isInstanceOf(IllegalArgumentException.class);

        IdentityVerification id = IdentityVerification.firstSubmission(1L, "KG", DocumentType.NATIONAL_ID, "AB1234", 10L, 12L, 11L, T);
        assertThat(id.getCurrentDocumentBackId()).isEqualTo(12L);
        IdentityVerification passport = IdentityVerification.firstSubmission(1L, "KG", DocumentType.INTERNATIONAL_PASSPORT, "AB1234", 10L, null, 11L, T);
        assertThat(passport.getCurrentDocumentBackId()).isNull();
    }

    @Test
    @DisplayName("resubmission follows the same rule and replaces the back side (an ID card -> passport switch drops it)")
    void resubmissionBackSide() {
        IdentityVerification iv = IdentityVerification.firstSubmission(1L, "KG", DocumentType.NATIONAL_ID, "AB1234", 10L, 12L, 11L, T);
        iv.reject(T);
        assertThatThrownBy(() -> iv.resubmit("KG", DocumentType.DRIVER_LICENSE, "DL1234", 20L, null, 21L, T))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(iv.getStatus()).isEqualTo(IdentityVerificationStatus.REJECTED);
        assertThat(iv.getCurrentDocumentBackId()).isEqualTo(12L);

        iv.resubmit("KG", DocumentType.INTERNATIONAL_PASSPORT, "PP1234", 20L, null, 21L, T);
        assertThat(iv.getCurrentDocumentBackId()).isNull();
        iv.reject(T);
        iv.resubmit("KG", DocumentType.DRIVER_LICENSE, "DL1234", 30L, 32L, 31L, T);
        assertThat(iv.getCurrentDocumentBackId()).isEqualTo(32L);
    }

    @Test
    @DisplayName("approval: a legacy ID card / driver licence without the back cannot be approved; a passport can")
    void approvalNeedsTheBack() {
        IdentityVerification legacyId = new IdentityVerification(1L, 1L, IdentityVerificationStatus.PENDING_REVIEW, "KG",
                DocumentType.NATIONAL_ID, "AB1234", 10L, null, 11L, T, T, 0L);
        assertThatThrownBy(() -> legacyId.approve(T)).isInstanceOf(IllegalStateException.class);
        assertThat(legacyId.getStatus()).isEqualTo(IdentityVerificationStatus.PENDING_REVIEW);

        IdentityVerification legacyDl = new IdentityVerification(2L, 2L, IdentityVerificationStatus.PENDING_REVIEW, "KG",
                DocumentType.DRIVER_LICENSE, "DL1234", 10L, null, 11L, T, T, 0L);
        assertThatThrownBy(() -> legacyDl.approve(T)).isInstanceOf(IllegalStateException.class);

        IdentityVerification passport = new IdentityVerification(3L, 3L, IdentityVerificationStatus.PENDING_REVIEW, "KG",
                DocumentType.INTERNATIONAL_PASSPORT, "PP1234", 10L, null, 11L, T, T, 0L);
        passport.approve(T);
        assertThat(passport.getStatus()).isEqualTo(IdentityVerificationStatus.VERIFIED);

        IdentityVerification withBack = new IdentityVerification(4L, 4L, IdentityVerificationStatus.PENDING_REVIEW, "KG",
                DocumentType.NATIONAL_ID, "AB1234", 10L, 12L, 11L, T, T, 0L);
        withBack.approve(T);
        assertThat(withBack.getStatus()).isEqualTo(IdentityVerificationStatus.VERIFIED);
    }

    @Test
    @DisplayName("history: the back reference exists only on a SUBMISSION")
    void historyBackReference() {
        IdentityVerificationHistory submission = IdentityVerificationHistory.submission(1L, "AB1234", 10L, 12L, T);
        assertThat(submission.getDocumentBackReferenceId()).isEqualTo(12L);
        assertThat(IdentityVerificationHistory.submission(1L, "AB1234", 10L, T).getDocumentBackReferenceId()).isNull();
        assertThatThrownBy(() -> new IdentityVerificationHistory(1L, 1L, IdentityEventType.APPROVAL, "AB1234", null, 12L, 5L,
                null, null, T)).isInstanceOf(IllegalArgumentException.class);
    }
}
