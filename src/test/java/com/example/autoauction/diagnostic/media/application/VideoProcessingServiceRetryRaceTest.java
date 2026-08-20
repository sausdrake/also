// diagnostic/media/application/VideoProcessingServiceRetryRaceTest.java
package com.example.autoauction.diagnostic.media.application;

import com.example.autoauction.diagnostic.media.domain.DiagnosticMedia;
import com.example.autoauction.diagnostic.media.domain.ProcessingStatus;
import com.example.autoauction.diagnostic.media.domain.UploadStatus;
import com.example.autoauction.diagnostic.media.domain.port.DiagnosticMediaRepository;
import com.example.autoauction.diagnostic.media.web.dto.MediaResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MEDIA-3.4 Phase 9, case 10: proves retryProcessing() re-reads the
 * authoritative state after retryFailedProcessing() returns false, rather
 * than treating every false as a conflict OR blindly assuming success -
 * mirrors VideoProcessingServiceDbFinalizationFailureTest's @MockBean
 * DiagnosticMediaRepository pattern exactly.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("MEDIA-3.4 Phase 9: retryProcessing() race handling")
class VideoProcessingServiceRetryRaceTest {

    @Autowired
    private VideoProcessingService videoProcessingService;

    @MockBean
    private DiagnosticMediaRepository mediaRepository;

    private DiagnosticMedia stateOf(Long id, UploadStatus uploadStatus, ProcessingStatus processingStatus) {
        DiagnosticMedia media = new DiagnosticMedia();
        media.setId(id);
        media.setUploadStatus(uploadStatus);
        media.setProcessingStatus(processingStatus);
        return media;
    }

    @Test
    @DisplayName("Гонка разрешается в PENDING (кто-то другой уже выиграл retry) -> 200-эквивалент, без ошибки")
    void raceResolvesToPending_ReturnsSuccessNotError() {
        Long id = 1L;
        DiagnosticMedia failed = stateOf(id, UploadStatus.READY, ProcessingStatus.FAILED);
        DiagnosticMedia pendingAfterRace = stateOf(id, UploadStatus.READY, ProcessingStatus.PENDING);

        when(mediaRepository.findById(id)).thenReturn(Optional.of(failed)).thenReturn(Optional.of(pendingAfterRace));
        when(mediaRepository.retryFailedProcessing(id)).thenReturn(false);

        MediaResponse response = videoProcessingService.retryProcessing(id);

        assertThat(response.processingStatus()).isEqualTo(ProcessingStatus.PENDING);
        verify(mediaRepository, times(2)).findById(id); // initial check + re-read after the race
    }

    @Test
    @DisplayName("Гонка НЕ разрешается (состояние всё ещё FAILED) -> явная ошибка, а не тихий успех")
    void raceDoesNotResolve_StillFailed_ThrowsExplicitly() {
        Long id = 2L;
        DiagnosticMedia failed = stateOf(id, UploadStatus.READY, ProcessingStatus.FAILED);

        when(mediaRepository.findById(id)).thenReturn(Optional.of(failed)); // same state on every call
        when(mediaRepository.retryFailedProcessing(id)).thenReturn(false);

        assertThatThrownBy(() -> videoProcessingService.retryProcessing(id))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Гонка разрешается в DELETED -> конфликт, а не тихий успех")
    void raceResolvesToDeleted_Conflict() {
        Long id = 3L;
        DiagnosticMedia failed = stateOf(id, UploadStatus.READY, ProcessingStatus.FAILED);
        DiagnosticMedia deletedAfterRace = stateOf(id, UploadStatus.DELETED, ProcessingStatus.FAILED);

        when(mediaRepository.findById(id)).thenReturn(Optional.of(failed)).thenReturn(Optional.of(deletedAfterRace));
        when(mediaRepository.retryFailedProcessing(id)).thenReturn(false);

        assertThatThrownBy(() -> videoProcessingService.retryProcessing(id))
                .isInstanceOf(IllegalStateException.class);
    }
}
