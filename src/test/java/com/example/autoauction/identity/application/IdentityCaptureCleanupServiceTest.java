package com.example.autoauction.identity.application;

import com.example.autoauction.diagnostic.media.domain.port.StorageClient;
import com.example.autoauction.identity.domain.port.IdentityDocumentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KYC-SLOT-01 - abandoned capture cleanup")
class IdentityCaptureCleanupServiceTest {

    private static final OffsetDateTime CUTOFF = IdentityCaptures.NOW.minusHours(24);

    @Mock private IdentityDocumentRepository documents;
    @Mock private StorageClient storage;

    @Test
    @DisplayName("cutoff is now - 24 h; the stored object goes first, then the row")
    void removesObjectThenRow() {
        IdentityCaptureCleanupService service = new IdentityCaptureCleanupService(documents, storage, IdentityCaptures.CLOCK);
        when(documents.findAbandonedCaptures(CUTOFF, IdentityCaptureCleanupService.BATCH))
                .thenReturn(List.of(IdentityCaptures.front(5L, "identity-documents/a", 1L)));
        when(documents.deleteAbandonedCapture(5L, CUTOFF)).thenReturn(true);

        assertThat(service.removeAbandonedCaptures()).isEqualTo(1);
        InOrder order = inOrder(storage, documents);
        order.verify(storage).deleteObject("identity-documents/a");
        order.verify(documents).deleteAbandonedCapture(5L, CUTOFF);
    }

    @Test
    @DisplayName("a storage failure keeps the row (retried next run) and does not stop the batch")
    void storageFailure_keepsRow() {
        IdentityCaptureCleanupService service = new IdentityCaptureCleanupService(documents, storage, IdentityCaptures.CLOCK);
        when(documents.findAbandonedCaptures(any(), eq(IdentityCaptureCleanupService.BATCH))).thenReturn(List.of(
                IdentityCaptures.front(5L, "identity-documents/a", 1L), IdentityCaptures.back(6L, "identity-documents/b", 1L)));
        doThrow(new RuntimeException("storage down")).when(storage).deleteObject("identity-documents/a");
        when(documents.deleteAbandonedCapture(eq(6L), any())).thenReturn(true);

        assertThat(service.removeAbandonedCaptures()).isEqualTo(1);
        verify(documents, never()).deleteAbandonedCapture(eq(5L), any());
    }
}
