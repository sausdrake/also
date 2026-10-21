package com.example.autoauction.identity.application;

import com.example.autoauction.diagnostic.media.infrastructure.storage.FakeStorageClient;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityCaptureSide;
import com.example.autoauction.identity.domain.IdentityDocument;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static com.example.autoauction.identity.application.IdentityCaptures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** KYC-CAPTURE-01: every check of {@link IdentityCaptureVerifier}, one refusal at a time. */
@ExtendWith(MockitoExtension.class)
@DisplayName("KYC-CAPTURE-01: IdentityCaptureVerifier")
class IdentityCaptureVerifierTest {

    @Mock private IdentityDocumentRepository documents;
    private FakeStorageClient storage;
    private IdentityCaptureVerifier verifier;

    private static final Long USER = 1L;
    private static final String KEY = "identity-documents/k";

    @BeforeEach
    void setUp() {
        storage = new FakeStorageClient();
        verifier = new IdentityCaptureVerifier(documents, storage);
    }

    private void stored(IdentityDocument capture, String contentType) {
        when(documents.findByStorageKey(capture.getStorageKey())).thenReturn(Optional.of(capture));
        if (contentType != null) {
            storage.simulateUpload(capture.getStorageKey(), new byte[]{1}, contentType);
        }
    }

    private IdentityDocument front(Long owner) {
        return IdentityCaptures.capture(10L, KEY, owner, IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, "image/jpeg");
    }

    private IdentityDocument require(IdentityCaptureSide side, DocumentType type, java.time.OffsetDateTime at) {
        return verifier.require(USER, KEY, side, type, at);
    }

    @Test
    @DisplayName("an owned, unused, fresh JPEG capture of the right side and type passes")
    void validCapturePasses() {
        stored(front(USER), "image/jpeg");
        assertThat(require(IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, NOW).getId()).isEqualTo(10L);
    }

    @Test
    void blankKeyRefused() {
        assertThatThrownBy(() -> verifier.require(USER, " ", IdentityCaptureSide.BACK, DocumentType.NATIONAL_ID, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("обратная сторона");
        assertThatThrownBy(() -> verifier.require(USER, null, IdentityCaptureSide.BACK, DocumentType.NATIONAL_ID, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownKeyRefused() {
        when(documents.findByStorageKey(KEY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> require(IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ownership: another user's capture is a SecurityException (403)")
    void foreignCaptureRefused() {
        stored(front(2L), "image/jpeg");
        assertThatThrownBy(() -> require(IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, NOW)).isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("a capture issued for another side, or a legacy row without a side, is refused")
    void wrongSideRefused() {
        stored(front(USER), "image/jpeg");
        assertThatThrownBy(() -> require(IdentityCaptureSide.BACK, DocumentType.NATIONAL_ID, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("не для этого шага");
    }

    @Test
    void legacyRowWithoutSideRefused() {
        when(documents.findByStorageKey(KEY)).thenReturn(Optional.of(new IdentityDocument(10L, KEY, USER, "image/jpeg", 3L, NOW)));
        assertThatThrownBy(() -> require(IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a capture issued for another document type is refused")
    void wrongDocumentTypeRefused() {
        stored(front(USER), "image/jpeg");
        assertThatThrownBy(() -> require(IdentityCaptureSide.FRONT, DocumentType.DRIVER_LICENSE, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("другого типа");
    }

    @Test
    @DisplayName("the selfie is not bound to a document type")
    void selfieIgnoresDocumentType() {
        stored(IdentityCaptures.selfie(11L, KEY, USER), "image/jpeg");
        assertThat(require(IdentityCaptureSide.SELFIE, null, NOW).getId()).isEqualTo(11L);
    }

    @Test
    @DisplayName("a consumed capture is a conflict (409)")
    void consumedRefused() {
        stored(new IdentityDocument(10L, KEY, USER, "image/jpeg", 3L, NOW, IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, NOW), "image/jpeg");
        assertThatThrownBy(() -> require(IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("expiry boundary: 15 minutes exactly passes, one second later is refused")
    void expiryBoundary() {
        stored(front(USER), "image/jpeg");
        assertThat(require(IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, NOW.plusMinutes(15))).isNotNull();
        assertThatThrownBy(() -> require(IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, NOW.plusMinutes(15).plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Срок действия");
    }

    @Test
    @DisplayName("a non-JPEG slot (legacy PDF/PNG) is refused")
    void nonJpegRowRefused() {
        stored(IdentityCaptures.capture(10L, KEY, USER, IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, "application/pdf"), "application/pdf");
        assertThatThrownBy(() -> require(IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a slot not issued as a JPEG capture is refused even when the stored bytes claim to be JPEG")
    void nonJpegRowWithJpegBytesRefused() {
        stored(IdentityCaptures.capture(10L, KEY, USER, IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, "image/png"), "image/jpeg");
        assertThatThrownBy(() -> require(IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JPEG");
    }

    @Test
    @DisplayName("bytes never uploaded, or stored with another content type, are refused")
    void storageChecked() {
        stored(front(USER), null);
        assertThatThrownBy(() -> require(IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("не был загружен");
        storage.simulateUpload(KEY, new byte[]{1}, "image/png");
        assertThatThrownBy(() -> require(IdentityCaptureSide.FRONT, DocumentType.NATIONAL_ID, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JPEG");
    }

    @Test
    @DisplayName("consume: every capture marked; one already marked -> 409 and the rest are not attempted")
    void consume() {
        IdentityDocument a = IdentityCaptures.front(1L, "a", USER), b = IdentityCaptures.back(2L, "b", USER), c = IdentityCaptures.selfie(3L, "c", USER);
        when(documents.markConsumed(1L, NOW)).thenReturn(true);
        when(documents.markConsumed(2L, NOW)).thenReturn(true);
        when(documents.markConsumed(3L, NOW)).thenReturn(true);
        verifier.consume(List.of(a, b, c), NOW);
        verify(documents).markConsumed(3L, NOW);

        when(documents.markConsumed(eq(1L), any())).thenReturn(false);
        assertThatThrownBy(() -> verifier.consume(List.of(a, b), NOW.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
        verify(documents, never()).markConsumed(2L, NOW.plusSeconds(1));
    }
}
