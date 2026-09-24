package com.example.autoauction.diagnostic.media.infrastructure.scheduler;

import com.example.autoauction.admin.application.SchedulerHealthSignal;
import com.example.autoauction.diagnostic.media.application.MediaReconciliationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Phase 79 (G-77-4): MediaReconciliationScheduler is a Family-A thin orchestrator - one delegated
 * call to {@link MediaReconciliationService#reconcile()}. This is diagnostic-media reconciliation,
 * unrelated to payment reconciliation. Storage/mandatory-video-gate interaction and reconciliation
 * business logic remain proven by {@code MediaReconciliationServiceIntegrationTest}, not
 * re-asserted here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MediaReconciliationScheduler")
class MediaReconciliationSchedulerTest {

    @Mock
    private MediaReconciliationService reconciliationService;

    @Mock
    private SchedulerHealthSignal schedulerHealthSignal;

    @InjectMocks
    private MediaReconciliationScheduler scheduler;

    @Test
    @DisplayName("should delegate to MediaReconciliationService.reconcile() and report success on a completed sweep")
    void shouldDelegateToServiceAndReportSuccess() {
        scheduler.reconcileDiagnosticMedia();

        verify(reconciliationService).reconcile();
        verify(schedulerHealthSignal).reportSuccess("MediaReconciliationScheduler.reconcileDiagnosticMedia");
        verifyNoMoreInteractions(schedulerHealthSignal);
    }

    @Test
    @DisplayName("when the reconciliation sweep throws, exception does not escape the scheduled method and reportFailure is emitted")
    void whenServiceThrows_ExceptionDoesNotEscapeAndFailureReported() {
        doThrow(new RuntimeException("simulated reconciliation sweep failure"))
                .when(reconciliationService).reconcile();

        scheduler.reconcileDiagnosticMedia(); // must not throw

        verify(schedulerHealthSignal).reportFailure("MediaReconciliationScheduler.reconcileDiagnosticMedia");
        verify(schedulerHealthSignal, never()).reportSuccess(anyString());
    }
}
