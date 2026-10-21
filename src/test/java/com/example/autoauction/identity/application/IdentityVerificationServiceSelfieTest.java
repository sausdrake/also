package com.example.autoauction.identity.application;

import com.example.autoauction.diagnostic.media.infrastructure.storage.FakeStorageClient;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityCaptureSide;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationHistoryRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
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
 * MVP-004: a submission must carry the user's own, actually uploaded live selfie as a separate
 * image - the same ownership/existence rules as the document image, plus "distinct file" and
 * "image" rules. Every refusal leaves no verification row and no history entry behind.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MVP-004: IdentityVerificationService.submit() - live selfie rules")
class IdentityVerificationServiceSelfieTest {

    @Mock private IdentityVerificationRepository identityVerificationRepository;
    @Mock private IdentityVerificationHistoryRepository historyRepository;
    @Mock private IdentityDocumentRepository documentRepository;

    private FakeStorageClient storageClient;
    private IdentityVerificationService service;

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final String DOC_KEY = "identity-documents/doc";
    private static final String SELFIE_KEY = "identity-documents/selfie";
    private static final String BACK_KEY = "identity-documents/back";

    @BeforeEach
    void setUp() {
        storageClient = new FakeStorageClient();
        Clock clock = IdentityCaptures.CLOCK;
        service = new IdentityVerificationService(identityVerificationRepository, historyRepository, documentRepository, storageClient,
                new IdentityCaptureVerifier(documentRepository, storageClient), clock);
        lenient().when(documentRepository.markConsumed(any(), any())).thenReturn(true);
        lenient().when(identityVerificationRepository.findByUserIdWithLock(any())).thenReturn(Optional.empty());
        lenient().when(identityVerificationRepository.save(any())).thenAnswer(inv -> {
            IdentityVerification iv = inv.getArgument(0);
            if (iv.getId() == null) {
                iv.setId(100L);
            }
            return iv;
        });
        ownedUpload(10L, DOC_KEY, USER_ID, IdentityCaptureSide.FRONT, "image/jpeg");
        ownedUpload(13L, BACK_KEY, USER_ID, IdentityCaptureSide.BACK, "image/jpeg");
    }

    private void ownedUpload(Long id, String key, Long owner, String mimeType) {
        ownedUpload(id, key, owner, IdentityCaptureSide.SELFIE, mimeType);
    }

    private void ownedUpload(Long id, String key, Long owner, IdentityCaptureSide side, String mimeType) {
        DocumentType type = side == IdentityCaptureSide.SELFIE ? null : DocumentType.NATIONAL_ID;
        lenient().when(documentRepository.findByStorageKey(key))
                .thenReturn(Optional.of(IdentityCaptures.capture(id, key, owner, side, type, mimeType)));
        storageClient.simulateUpload(key, new byte[]{1, 2, 3}, mimeType);
    }

    private IdentityVerification submit(String selfieKey) {
        return service.submit(USER_ID, "KG", DocumentType.NATIONAL_ID, "AB1234567", DOC_KEY, BACK_KEY, selfieKey);
    }

    private void assertNothingPersisted() {
        verify(identityVerificationRepository, never()).save(any());
        verify(historyRepository, never()).save(any());
    }

    @Test
    @DisplayName("an owned, uploaded selfie image is recorded next to the document")
    void ownedUploadedSelfie_isRecorded() {
        ownedUpload(11L, SELFIE_KEY, USER_ID, "image/jpeg");

        IdentityVerification result = submit(SELFIE_KEY);

        assertThat(result.getCurrentDocumentId()).isEqualTo(10L);
        assertThat(result.getCurrentSelfieDocumentId()).isEqualTo(11L);
    }

    @ParameterizedTest(name = "selfie key [{0}] is refused")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void missingSelfie_isRefused(String selfieKey) {
        assertThatThrownBy(() -> submit(selfieKey)).isInstanceOf(IllegalArgumentException.class);
        assertNothingPersisted();
    }

    @Test
    @DisplayName("the document image cannot double as the selfie")
    void documentImageAsSelfie_isRefused() {
        assertThatThrownBy(() -> submit(DOC_KEY)).isInstanceOf(IllegalArgumentException.class);
        assertNothingPersisted();
    }

    @Test
    @DisplayName("a selfie key that was never issued is refused")
    void unknownSelfieKey_isRefused() {
        when(documentRepository.findByStorageKey("identity-documents/made-up")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> submit("identity-documents/made-up")).isInstanceOf(IllegalArgumentException.class);
        assertNothingPersisted();
    }

    @Test
    @DisplayName("another user's selfie is refused as a security violation")
    void foreignSelfie_isRefused() {
        ownedUpload(12L, "identity-documents/foreign-selfie", OTHER_USER_ID, "image/jpeg");

        assertThatThrownBy(() -> submit("identity-documents/foreign-selfie")).isInstanceOf(SecurityException.class);
        assertNothingPersisted();
    }

    @Test
    @DisplayName("an issued selfie slot that was never uploaded is refused")
    void selfieNeverUploaded_isRefused() {
        when(documentRepository.findByStorageKey(SELFIE_KEY))
                .thenReturn(Optional.of(IdentityCaptures.selfie(11L, SELFIE_KEY, USER_ID)));

        assertThatThrownBy(() -> submit(SELFIE_KEY)).isInstanceOf(IllegalArgumentException.class);
        assertNothingPersisted();
    }

    @Test
    @DisplayName("a selfie that is not an image is refused")
    void nonImageSelfie_isRefused() {
        ownedUpload(11L, SELFIE_KEY, USER_ID, "application/pdf");

        assertThatThrownBy(() -> submit(SELFIE_KEY)).isInstanceOf(IllegalArgumentException.class);
        assertNothingPersisted();
    }

    @Test
    @DisplayName("resubmission after a rejection replaces both the document and the selfie")
    void resubmission_replacesSelfie() {
        IdentityVerification rejected = IdentityVerification.firstSubmission(
                USER_ID, "KG", DocumentType.NATIONAL_ID, "OLD1234", 5L, 5L, 6L, OffsetDateTime.now());
        rejected.reject(OffsetDateTime.now());
        when(identityVerificationRepository.findByUserIdWithLock(USER_ID)).thenReturn(Optional.of(rejected));
        ownedUpload(11L, SELFIE_KEY, USER_ID, "image/jpeg");

        IdentityVerification result = submit(SELFIE_KEY);

        assertThat(result.getCurrentDocumentId()).isEqualTo(10L);
        assertThat(result.getCurrentSelfieDocumentId()).isEqualTo(11L);
    }
}
