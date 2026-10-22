package com.example.autoauction.identity.application;

import com.example.autoauction.auction.application.RateLimitException;
import com.example.autoauction.diagnostic.media.infrastructure.storage.FakeStorageClient;
import com.example.autoauction.identity.domain.DocumentType;
import com.example.autoauction.identity.domain.IdentityCaptureSide;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationHistoryRepository;
import com.example.autoauction.identity.domain.port.IdentityVerificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("KYC-SLOT-01 - capture slot issuance is rate limited")
class IdentityCaptureSlotRateLimitTest {

    @Mock private IdentityVerificationRepository identityVerificationRepository;
    @Mock private IdentityVerificationHistoryRepository historyRepository;
    @Mock private IdentityDocumentRepository documentRepository;

    @Test
    @DisplayName("over the hourly limit: RateLimitException (429), no slot row, no presigned URL")
    void overLimit_refusedWithoutSideEffects() {
        FakeStorageClient storage = new FakeStorageClient();
        IdentityVerificationService service = new IdentityVerificationService(identityVerificationRepository, historyRepository,
                documentRepository, storage, new IdentityCaptureVerifier(documentRepository, storage),
                new IdentityCaptureRateLimiter(), IdentityCaptures.CLOCK);
        IntStream.range(0, IdentityCaptureRateLimiter.SLOTS_PER_HOUR)
                .forEach(i -> service.requestUploadSlot(1L, "image/jpeg", IdentityCaptureSide.SELFIE, null));

        assertThatThrownBy(() -> service.requestUploadSlot(1L, "image/jpeg", IdentityCaptureSide.DATA_PAGE,
                DocumentType.INTERNATIONAL_PASSPORT)).isInstanceOf(RateLimitException.class);
        verify(documentRepository, times(IdentityCaptureRateLimiter.SLOTS_PER_HOUR)).save(any());
        assertThat(service.requestUploadSlot(2L, "image/jpeg", IdentityCaptureSide.SELFIE, null).storageKey())
                .startsWith("identity-documents/");
    }
}
