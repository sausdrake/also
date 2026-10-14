package com.example.autoauction.identity.application;

import com.example.autoauction.diagnostic.media.infrastructure.storage.FakeStorageClient;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.IdentityVerificationStatus;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationHistoryRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 4 §4 (the exact 5-row submission state table) / Phase 3.1 §2: proves every branch of
 * {@link IdentityVerificationService#submit} itself - not just the underlying domain guards
 * exercised directly in {@code IdentityVerificationTest}. The distinction matters: for the
 * PENDING_REVIEW+different-identity and VERIFIED cases, the SERVICE throws before ever calling
 * {@link IdentityVerification#resubmit}, so a domain-only test cannot observe this behavior.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IdentityVerificationService.submit() - exact 5-row state table")
class IdentityVerificationServiceSubmissionRuleTest {

    @Mock private IdentityVerificationRepository identityVerificationRepository;
    @Mock private IdentityVerificationHistoryRepository historyRepository;
    @Mock private IdentityDocumentRepository documentRepository;

    private FakeStorageClient storageClient;
    private IdentityVerificationService service;

    private static final Long USER_ID = 1L;
    private static final String KEY = "identity-documents/rule-test";
    private static final String SELFIE_KEY = "identity-documents/rule-test-selfie";

    @BeforeEach
    void setUp() {
        storageClient = new FakeStorageClient();
        storageClient.simulateUpload(KEY, new byte[]{1, 2, 3}, "image/jpeg");
        storageClient.simulateUpload(SELFIE_KEY, new byte[]{4, 5, 6}, "image/jpeg");
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new IdentityVerificationService(identityVerificationRepository, historyRepository, documentRepository, storageClient, clock);
        IdentityDocument ownDoc = new IdentityDocument(10L, KEY, USER_ID, "image/jpeg", 3L, OffsetDateTime.now());
        lenient().when(documentRepository.findByStorageKey(KEY)).thenReturn(Optional.of(ownDoc));
        IdentityDocument ownSelfie = new IdentityDocument(11L, SELFIE_KEY, USER_ID, "image/jpeg", 3L, OffsetDateTime.now());
        lenient().when(documentRepository.findByStorageKey(SELFIE_KEY)).thenReturn(Optional.of(ownSelfie));
        lenient().when(identityVerificationRepository.save(any())).thenAnswer(inv -> {
            IdentityVerification iv = inv.getArgument(0);
            if (iv.getId() == null) {
                iv.setId(100L);
            }
            return iv;
        });
    }

    @Test
    @DisplayName("Row 1: no existing row -> new PENDING_REVIEW row created")
    void noExistingRow_CreatesNewPendingReview() {
        when(identityVerificationRepository.findByUserIdWithLock(USER_ID)).thenReturn(Optional.empty());

        IdentityVerification result = service.submit(USER_ID, "KG", DocumentType.NATIONAL_ID, "AB1234567", KEY, SELFIE_KEY);

        assertThat(result.getStatus()).isEqualTo(IdentityVerificationStatus.PENDING_REVIEW);
        verify(historyRepository).save(any());
    }

    @Test
    @DisplayName("Row 2: PENDING_REVIEW + SAME identity -> idempotent 200, no new history row")
    void pendingReview_SameIdentity_IdempotentNoStateChange() {
        IdentityVerification existing = IdentityVerification.firstSubmission(
                USER_ID, "KG", DocumentType.NATIONAL_ID, "AB1234567", 10L, 10L, OffsetDateTime.now());
        existing.setVersion(0L);
        when(identityVerificationRepository.findByUserIdWithLock(USER_ID)).thenReturn(Optional.of(existing));

        IdentityVerification result = service.submit(USER_ID, "KG", DocumentType.NATIONAL_ID, "AB1234567", KEY, SELFIE_KEY);

        assertThat(result.getStatus()).isEqualTo(IdentityVerificationStatus.PENDING_REVIEW);
        verify(identityVerificationRepository, never()).save(any());
        verify(historyRepository, never()).save(any());
    }

    @Test
    @DisplayName("Row 3: PENDING_REVIEW + DIFFERENT identity -> fail closed, zero state change")
    void pendingReview_DifferentIdentity_FailsClosedNoStateChange() {
        IdentityVerification existing = IdentityVerification.firstSubmission(
                USER_ID, "KG", DocumentType.NATIONAL_ID, "OLDIDENT1", 10L, 10L, OffsetDateTime.now());
        when(identityVerificationRepository.findByUserIdWithLock(USER_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submit(USER_ID, "KG", DocumentType.INTERNATIONAL_PASSPORT, "NEWIDENT1", KEY, SELFIE_KEY))
                .isInstanceOf(IllegalStateException.class);

        verify(identityVerificationRepository, never()).save(any());
        verify(historyRepository, never()).save(any());
        assertThat(existing.getCanonicalDocumentNumber()).isEqualTo("OLDIDENT1");
    }

    @Test
    @DisplayName("Row 4: VERIFIED -> fail closed, replacement not supported in MVP")
    void verified_FailsClosed() {
        IdentityVerification existing = IdentityVerification.firstSubmission(
                USER_ID, "KG", DocumentType.NATIONAL_ID, "VERIFIED1", 10L, 10L, OffsetDateTime.now());
        existing.approve(OffsetDateTime.now());
        when(identityVerificationRepository.findByUserIdWithLock(USER_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submit(USER_ID, "KG", DocumentType.NATIONAL_ID, "DIFFERENT1", KEY, SELFIE_KEY))
                .isInstanceOf(IllegalStateException.class);

        verify(identityVerificationRepository, never()).save(any());
        verify(historyRepository, never()).save(any());
        assertThat(existing.getStatus()).isEqualTo(IdentityVerificationStatus.VERIFIED);
    }

    @Test
    @DisplayName("Row 5a: REJECTED -> resubmission allowed, new PENDING_REVIEW")
    void rejected_ResubmissionAllowed() {
        IdentityVerification existing = IdentityVerification.firstSubmission(
                USER_ID, "KG", DocumentType.NATIONAL_ID, "REJECTED1", 10L, 10L, OffsetDateTime.now());
        existing.reject(OffsetDateTime.now());
        when(identityVerificationRepository.findByUserIdWithLock(USER_ID)).thenReturn(Optional.of(existing));

        IdentityVerification result = service.submit(USER_ID, "KG", DocumentType.NATIONAL_ID, "NEWAFTERREJ", KEY, SELFIE_KEY);

        assertThat(result.getStatus()).isEqualTo(IdentityVerificationStatus.PENDING_REVIEW);
        assertThat(result.getCanonicalDocumentNumber()).isEqualTo("NEWAFTERREJ");
    }

    @Test
    @DisplayName("Row 5b: REVOKED -> resubmission allowed, new PENDING_REVIEW")
    void revoked_ResubmissionAllowed() {
        IdentityVerification existing = IdentityVerification.firstSubmission(
                USER_ID, "KG", DocumentType.NATIONAL_ID, "REVOKED1", 10L, 10L, OffsetDateTime.now());
        existing.approve(OffsetDateTime.now());
        existing.revoke(OffsetDateTime.now());
        when(identityVerificationRepository.findByUserIdWithLock(USER_ID)).thenReturn(Optional.of(existing));

        IdentityVerification result = service.submit(USER_ID, "KG", DocumentType.NATIONAL_ID, "NEWAFTERREV", KEY, SELFIE_KEY);

        assertThat(result.getStatus()).isEqualTo(IdentityVerificationStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("unsupported issuing country rejected before any repository interaction")
    void unsupportedIssuingCountry_Rejected() {
        assertThatThrownBy(() -> service.submit(USER_ID, "RU", DocumentType.NATIONAL_ID, "AB1234567", KEY, SELFIE_KEY))
                .isInstanceOf(IllegalArgumentException.class);

        verify(identityVerificationRepository, never()).findByUserIdWithLock(any());
        verify(identityVerificationRepository, never()).save(any());
    }
}
