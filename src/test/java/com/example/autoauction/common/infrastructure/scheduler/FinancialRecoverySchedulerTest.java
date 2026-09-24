package com.example.autoauction.common.infrastructure.scheduler;

import com.example.autoauction.admin.application.SchedulerHealthSignal;
import com.example.autoauction.common.application.FinancialRecoveryService;
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
 * Phase 79 (G-77-4): FinancialRecoveryScheduler is a Family-A thin orchestrator - one delegated
 * call to {@link FinancialRecoveryService#recoverPending()}. Transaction/retry/idempotency
 * semantics of the recovery sweep itself remain proven by
 * {@code FinancialRecoveryServiceIntegrationTest}, not re-asserted here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FinancialRecoveryScheduler")
class FinancialRecoverySchedulerTest {

    @Mock
    private FinancialRecoveryService recoveryService;

    @Mock
    private SchedulerHealthSignal schedulerHealthSignal;

    @InjectMocks
    private FinancialRecoveryScheduler scheduler;

    @Test
    @DisplayName("should delegate to FinancialRecoveryService.recoverPending() and report success on a completed sweep")
    void shouldDelegateToServiceAndReportSuccess() {
        scheduler.recoverFailedFinancialOperations();

        verify(recoveryService).recoverPending();
        verify(schedulerHealthSignal).reportSuccess("FinancialRecoveryScheduler.recoverFailedFinancialOperations");
        verifyNoMoreInteractions(schedulerHealthSignal);
    }

    @Test
    @DisplayName("when the recovery sweep throws, exception does not escape the scheduled method and reportFailure is emitted")
    void whenServiceThrows_ExceptionDoesNotEscapeAndFailureReported() {
        doThrow(new RuntimeException("simulated recovery sweep failure"))
                .when(recoveryService).recoverPending();

        scheduler.recoverFailedFinancialOperations(); // must not throw

        verify(schedulerHealthSignal).reportFailure("FinancialRecoveryScheduler.recoverFailedFinancialOperations");
        verify(schedulerHealthSignal, never()).reportSuccess(anyString());
    }
}
