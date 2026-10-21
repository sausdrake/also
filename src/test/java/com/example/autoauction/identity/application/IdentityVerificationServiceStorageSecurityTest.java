package com.example.autoauction.identity.application;

import com.example.autoauction.diagnostic.media.infrastructure.storage.FakeStorageClient;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityCaptureSide;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.IdentityVerificationStatus;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationHistoryRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

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
 * Phase 4 §15 storage tests: proves the server-generated-key, ownership-row, headObject-verified
 * flow (Phase 3.1 §4) end to end at the application-service layer, using the real
 * {@link FakeStorageClient} (not a mock) so {@code headObject} is genuinely consulted rather than
 * just stubbed-and-ignored. Repositories are mocked since only the storage-security mechanism
 * itself is under test here - the submission state-machine is already covered elsewhere.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IdentityVerificationService - document-upload security mechanism")
class IdentityVerificationServiceStorageSecurityTest {

    @Mock private IdentityVerificationRepository identityVerificationRepository;
    @Mock private IdentityVerificationHistoryRepository historyRepository;
    @Mock private IdentityDocumentRepository documentRepository;

    private FakeStorageClient storageClient;
    private IdentityVerificationService service;

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final String SELFIE_KEY = "identity-documents/own-selfie";
    private static final String BACK_KEY = "identity-documents/own-back";

    @BeforeEach
    void setUp() {
        storageClient = new FakeStorageClient();
        Clock clock = IdentityCaptures.CLOCK;
        service = new IdentityVerificationService(identityVerificationRepository, historyRepository, documentRepository, storageClient,
                new IdentityCaptureVerifier(documentRepository, storageClient), clock);
        lenient().when(documentRepository.markConsumed(any(), any())).thenReturn(true);
        lenient().when(identityVerificationRepository.findByUserIdWithLock(any())).thenReturn(Optional.empty());
        // MVP-004: every submission also carries the user's own, uploaded live selfie.
        IdentityDocument ownSelfie = IdentityCaptures.selfie(11L, SELFIE_KEY, USER_ID);
        lenient().when(documentRepository.findByStorageKey(SELFIE_KEY)).thenReturn(Optional.of(ownSelfie));
        storageClient.simulateUpload(SELFIE_KEY, new byte[]{4, 5, 6}, "image/jpeg");
        // KYC-CAPTURE-01: an ID card submission also carries its own BACK capture.
        lenient().when(documentRepository.findByStorageKey(BACK_KEY)).thenReturn(Optional.of(IdentityCaptures.back(12L, BACK_KEY, USER_ID)));
        storageClient.simulateUpload(BACK_KEY, new byte[]{7, 8, 9}, "image/jpeg");
        // Simulates the real repository adapter assigning a generated id on first insert - a bare
        // passthrough stub would leave getId() null and break history.save()'s own NPE guard.
        lenient().when(identityVerificationRepository.save(any())).thenAnswer(inv -> {
            IdentityVerification iv = inv.getArgument(0);
            if (iv.getId() == null) {
                iv.setId(100L);
            }
            return iv;
        });
    }

    @Test
    @DisplayName("requestUploadSlot() generates a server-chosen key the client never influences, and persists ownership immediately")
    void requestUploadSlot_GeneratesServerKey_AndPersistsOwnershipImmediately() {
        IdentityUploadSlot slot = service.requestUploadSlot(USER_ID, "image/jpeg", IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID);

        assertThat(slot.storageKey()).startsWith("identity-documents/");
        verify(documentRepository).save(argThatOwnedBy(USER_ID));
    }

    private IdentityDocument argThatOwnedBy(Long userId) {
        return org.mockito.ArgumentMatchers.argThat(d -> d != null && d.isOwnedBy(userId));
    }

    @Test
    @DisplayName("submit() rejects a storageKey that was never issued by requestUploadSlot() (arbitrary client-chosen key)")
    void submit_ArbitraryUnknownKey_Rejected() {
        when(documentRepository.findByStorageKey("client-made-up-key")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(USER_ID, "KG", DocumentType.NATIONAL_ID, "AB1234567", "client-made-up-key", BACK_KEY, SELFIE_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("submit() rejects a real key owned by a DIFFERENT user (foreign-user-key)")
    void submit_ForeignUserKey_Rejected() {
        IdentityDocument foreignDoc = IdentityCaptures.front(10L, "identity-documents/foreign", OTHER_USER_ID);
        when(documentRepository.findByStorageKey("identity-documents/foreign")).thenReturn(Optional.of(foreignDoc));

        assertThatThrownBy(() -> service.submit(USER_ID, "KG", DocumentType.NATIONAL_ID, "AB1234567", "identity-documents/foreign", BACK_KEY, SELFIE_KEY))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("submit() rejects a real, owned key whose object was never actually uploaded (headObject empty)")
    void submit_KeyNeverActuallyUploaded_Rejected() {
        IdentityDocument ownDoc = IdentityCaptures.front(10L, "identity-documents/never-uploaded", USER_ID);
        when(documentRepository.findByStorageKey("identity-documents/never-uploaded")).thenReturn(Optional.of(ownDoc));
        // Deliberately never call storageClient.simulateUpload() - the object genuinely does not exist.

        assertThatThrownBy(() -> service.submit(USER_ID, "KG", DocumentType.NATIONAL_ID, "AB1234567", "identity-documents/never-uploaded", BACK_KEY, SELFIE_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("submit() succeeds for a real, owned key whose object was actually uploaded - headObject is genuinely consulted, not bypassed")
    void submit_OwnedAndActuallyUploadedKey_Succeeds() {
        String key = "identity-documents/real-upload";
        IdentityDocument ownDoc = IdentityCaptures.front(10L, key, USER_ID);
        when(documentRepository.findByStorageKey(key)).thenReturn(Optional.of(ownDoc));
        storageClient.simulateUpload(key, new byte[]{1, 2, 3}, "image/jpeg");

        IdentityVerification result = service.submit(USER_ID, "KG", DocumentType.NATIONAL_ID, "AB1234567", key, BACK_KEY, SELFIE_KEY);

        assertThat(result.getStatus()).isEqualTo(IdentityVerificationStatus.PENDING_REVIEW);
        assertThat(result.getCurrentDocumentId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("KYC-CAPTURE-01 (replaces the Phase 3.1 §4 reuse allowance): a capture already used in a submission is refused - a resubmission needs new captures")
    void submit_OwnKeyAlreadyConsumed_Refused() {
        String key = "identity-documents/reused";
        IdentityDocument consumed = new IdentityDocument(10L, key, USER_ID, "image/jpeg", 3L, IdentityCaptures.NOW,
                IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, IdentityCaptures.NOW);
        when(documentRepository.findByStorageKey(key)).thenReturn(Optional.of(consumed));
        storageClient.simulateUpload(key, new byte[]{1, 2, 3}, "image/jpeg");

        IdentityVerification first = IdentityVerification.firstSubmission(USER_ID, "KG", DocumentType.NATIONAL_ID, "AB1234567", 10L, 10L, 10L, OffsetDateTime.now());
        first.reject(OffsetDateTime.now());
        lenient().when(identityVerificationRepository.findByUserIdWithLock(USER_ID)).thenReturn(Optional.of(first));

        assertThatThrownBy(() -> service.submit(USER_ID, "KG", DocumentType.NATIONAL_ID, "AB1234567", key, BACK_KEY, SELFIE_KEY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("уже использован");
        verify(identityVerificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("getOwnDocumentUrl() denies a user whose own verification row points to a document NOT owned by them")
    void getOwnDocumentUrl_DocumentNotOwnedByCaller_Denied() {
        IdentityVerification iv = IdentityVerification.firstSubmission(USER_ID, "KG", DocumentType.NATIONAL_ID, "AB1234567", 10L, 10L, 10L, OffsetDateTime.now());
        when(identityVerificationRepository.findByUserId(USER_ID)).thenReturn(Optional.of(iv));
        IdentityDocument foreignDoc = new IdentityDocument(10L, "identity-documents/foreign", OTHER_USER_ID, "image/jpeg", 100L, OffsetDateTime.now());
        when(documentRepository.findById(10L)).thenReturn(Optional.of(foreignDoc));

        assertThatThrownBy(() -> service.getOwnDocumentUrl(USER_ID)).isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("getOwnDocumentUrl() returns a fresh presigned read URL for the caller's own document")
    void getOwnDocumentUrl_OwnDocument_ReturnsFreshUrl() {
        IdentityVerification iv = IdentityVerification.firstSubmission(USER_ID, "KG", DocumentType.NATIONAL_ID, "AB1234567", 10L, 10L, 10L, OffsetDateTime.now());
        when(identityVerificationRepository.findByUserId(USER_ID)).thenReturn(Optional.of(iv));
        IdentityDocument ownDoc = new IdentityDocument(10L, "identity-documents/own", USER_ID, "image/jpeg", 100L, OffsetDateTime.now());
        when(documentRepository.findById(10L)).thenReturn(Optional.of(ownDoc));

        var url = service.getOwnDocumentUrl(USER_ID);
        assertThat(url.toString()).contains("identity-documents/own").contains("signed=1");
    }
}
