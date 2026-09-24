package com.example.autoauction.payment.infrastructure.scheduler;

import com.example.autoauction.admin.application.SchedulerHealthSignal;
import com.example.autoauction.payment.application.PaymentConfirmationOrchestrator;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 79 (G-77-4): PaymentConfirmationScheduler is a Family-B two-phase scheduler
 * (candidate-scan then per-candidate loop). Actual Payment state transitions, provider
 * interaction and duplicate protection remain proven by
 * {@code PaymentConfirmationServiceIntegrationTest} and its Concurrency/Rollback siblings -
 * not re-asserted here. This class proves only the scheduler wrapper's own delegation,
 * candidate isolation and health-signal contract.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentConfirmationScheduler")
class PaymentConfirmationSchedulerTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentConfirmationOrchestrator paymentConfirmationOrchestrator;

    @Mock
    private SchedulerHealthSignal schedulerHealthSignal;

    @InjectMocks
    private PaymentConfirmationScheduler scheduler;

    @Test
    @DisplayName("given zero candidates, no confirmation is attempted and the sweep still reports success")
    void givenZeroCandidates_NoConfirmationAttemptedAndSweepSucceeds() {
        when(paymentRepository.findConfirmationCandidateReferences()).thenReturn(List.of());

        scheduler.pollPendingConfirmations();

        verify(paymentConfirmationOrchestrator, never()).confirmPayment(anyString());
        verify(schedulerHealthSignal).reportSuccess("PaymentConfirmationScheduler.pollPendingConfirmations");
        verify(schedulerHealthSignal, never()).reportFailure(anyString());
    }

    @Test
    @DisplayName("given multiple candidates, every candidate is confirmed")
    void givenMultipleCandidates_EveryCandidateIsConfirmed() {
        when(paymentRepository.findConfirmationCandidateReferences())
                .thenReturn(List.of("ref-1", "ref-2", "ref-3"));
        when(paymentConfirmationOrchestrator.confirmPayment(anyString())).thenReturn(mock(Payment.class));

        scheduler.pollPendingConfirmations();

        verify(paymentConfirmationOrchestrator).confirmPayment("ref-1");
        verify(paymentConfirmationOrchestrator).confirmPayment("ref-2");
        verify(paymentConfirmationOrchestrator).confirmPayment("ref-3");
        verify(schedulerHealthSignal).reportSuccess("PaymentConfirmationScheduler.pollPendingConfirmations");
    }

    @Test
    @DisplayName("when one candidate's confirmation fails, later candidates are still processed and the sweep still reports success")
    void whenOneCandidateFails_LaterCandidatesStillProcessedAndSweepStillSucceeds() {
        when(paymentRepository.findConfirmationCandidateReferences())
                .thenReturn(List.of("ref-1", "ref-2"));
        when(paymentConfirmationOrchestrator.confirmPayment("ref-1"))
                .thenThrow(new RuntimeException("simulated confirmation failure"));
        when(paymentConfirmationOrchestrator.confirmPayment("ref-2")).thenReturn(mock(Payment.class));

        scheduler.pollPendingConfirmations(); // must not throw

        verify(paymentConfirmationOrchestrator).confirmPayment("ref-1");
        verify(paymentConfirmationOrchestrator).confirmPayment("ref-2");
        verify(schedulerHealthSignal).reportSuccess("PaymentConfirmationScheduler.pollPendingConfirmations");
        verify(schedulerHealthSignal, never()).reportFailure(anyString());
    }

    @Test
    @DisplayName("when the candidate scan itself fails, no confirmation is attempted, reportFailure fires, reportSuccess does not, and the exception does not escape")
    void whenCandidateScanFails_NoConfirmationAttemptedAndFailureReported() {
        when(paymentRepository.findConfirmationCandidateReferences())
                .thenThrow(new RuntimeException("simulated candidate scan failure"));

        scheduler.pollPendingConfirmations(); // must not throw

        verify(paymentConfirmationOrchestrator, never()).confirmPayment(anyString());
        verify(schedulerHealthSignal).reportFailure("PaymentConfirmationScheduler.pollPendingConfirmations");
        verify(schedulerHealthSignal, never()).reportSuccess(anyString());
        verifyNoMoreInteractions(schedulerHealthSignal);
    }

    @Test
    @DisplayName("candidate scan is performed before any confirmation attempt")
    void candidateScanIsPerformedBeforeAnyConfirmationAttempt() {
        when(paymentRepository.findConfirmationCandidateReferences()).thenReturn(List.of("ref-1"));
        when(paymentConfirmationOrchestrator.confirmPayment(anyString())).thenReturn(mock(Payment.class));

        scheduler.pollPendingConfirmations();

        InOrder inOrder = org.mockito.Mockito.inOrder(paymentRepository, paymentConfirmationOrchestrator);
        inOrder.verify(paymentRepository).findConfirmationCandidateReferences();
        inOrder.verify(paymentConfirmationOrchestrator).confirmPayment("ref-1");
    }
}
