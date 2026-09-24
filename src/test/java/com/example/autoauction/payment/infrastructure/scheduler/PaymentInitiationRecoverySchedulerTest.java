package com.example.autoauction.payment.infrastructure.scheduler;

import com.example.autoauction.admin.application.SchedulerHealthSignal;
import com.example.autoauction.payment.application.PaymentCreationOrchestrator;
import com.example.autoauction.payment.application.PermanentInitiationEvidenceException;
import com.example.autoauction.payment.domain.Payment;
import com.example.autoauction.payment.domain.PaymentStatus;
import com.example.autoauction.payment.domain.port.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 79 (G-77-4): PaymentInitiationRecoveryScheduler is a Family-B two-phase scheduler with
 * an additional distinct catch branch for {@link PermanentInitiationEvidenceException}. Actual
 * recovery state transitions, provider non-interaction and rollback semantics remain proven by
 * {@code PaymentInitiationRecoveryPgIntegrationTest} - not re-asserted here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentInitiationRecoveryScheduler")
class PaymentInitiationRecoverySchedulerTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentCreationOrchestrator paymentCreationOrchestrator;

    @Mock
    private SchedulerHealthSignal schedulerHealthSignal;

    @InjectMocks
    private PaymentInitiationRecoveryScheduler scheduler;

    private Payment recoveredPayment() {
        Payment payment = org.mockito.Mockito.mock(Payment.class);
        when(payment.getStatus()).thenReturn(PaymentStatus.PENDING);
        return payment;
    }

    @Test
    @DisplayName("given zero candidates, no recovery is attempted and the sweep still reports success")
    void givenZeroCandidates_NoRecoveryAttemptedAndSweepSucceeds() {
        when(paymentRepository.findCreatedWithInitiationEvidenceCandidateIds()).thenReturn(List.of());

        scheduler.recoverInterruptedInitiations();

        verify(paymentCreationOrchestrator, never()).recoverFromInitiationEvidence(anyLong());
        verify(schedulerHealthSignal).reportSuccess("PaymentInitiationRecoveryScheduler.recoverInterruptedInitiations");
        verify(schedulerHealthSignal, never()).reportFailure(anyString());
    }

    @Test
    @DisplayName("given multiple candidates, every candidate is offered for recovery")
    void givenMultipleCandidates_EveryCandidateIsOfferedForRecovery() {
        when(paymentRepository.findCreatedWithInitiationEvidenceCandidateIds()).thenReturn(List.of(1L, 2L, 3L));
        Payment payment = recoveredPayment();
        when(paymentCreationOrchestrator.recoverFromInitiationEvidence(anyLong())).thenReturn(payment);

        scheduler.recoverInterruptedInitiations();

        verify(paymentCreationOrchestrator).recoverFromInitiationEvidence(1L);
        verify(paymentCreationOrchestrator).recoverFromInitiationEvidence(2L);
        verify(paymentCreationOrchestrator).recoverFromInitiationEvidence(3L);
        verify(schedulerHealthSignal).reportSuccess("PaymentInitiationRecoveryScheduler.recoverInterruptedInitiations");
    }

    @Test
    @DisplayName("when one candidate's recovery throws an ordinary exception, later candidates are still processed and the sweep still reports success")
    void whenOneCandidateThrowsOrdinaryException_LaterCandidatesStillProcessedAndSweepStillSucceeds() {
        when(paymentRepository.findCreatedWithInitiationEvidenceCandidateIds()).thenReturn(List.of(1L, 2L));
        when(paymentCreationOrchestrator.recoverFromInitiationEvidence(1L))
                .thenThrow(new RuntimeException("simulated recovery failure"));
        Payment payment = recoveredPayment();
        when(paymentCreationOrchestrator.recoverFromInitiationEvidence(2L)).thenReturn(payment);

        scheduler.recoverInterruptedInitiations(); // must not throw

        verify(paymentCreationOrchestrator).recoverFromInitiationEvidence(1L);
        verify(paymentCreationOrchestrator).recoverFromInitiationEvidence(2L);
        verify(schedulerHealthSignal).reportSuccess("PaymentInitiationRecoveryScheduler.recoverInterruptedInitiations");
        verify(schedulerHealthSignal, never()).reportFailure(anyString());
    }

    @Test
    @DisplayName("when one candidate throws PermanentInitiationEvidenceException, its distinct catch branch is taken and later candidates are still processed")
    void whenOneCandidateThrowsPermanentInitiationEvidenceException_DistinctBranchTakenAndLaterCandidatesStillProcessed() {
        when(paymentRepository.findCreatedWithInitiationEvidenceCandidateIds()).thenReturn(List.of(1L, 2L));
        when(paymentCreationOrchestrator.recoverFromInitiationEvidence(1L))
                .thenThrow(new PermanentInitiationEvidenceException("simulated permanent evidence problem"));
        Payment payment = recoveredPayment();
        when(paymentCreationOrchestrator.recoverFromInitiationEvidence(2L)).thenReturn(payment);

        scheduler.recoverInterruptedInitiations(); // must not throw

        verify(paymentCreationOrchestrator).recoverFromInitiationEvidence(1L);
        verify(paymentCreationOrchestrator).recoverFromInitiationEvidence(2L);
        verify(schedulerHealthSignal).reportSuccess("PaymentInitiationRecoveryScheduler.recoverInterruptedInitiations");
        verify(schedulerHealthSignal, never()).reportFailure(anyString());
    }

    @Test
    @DisplayName("when the candidate scan itself fails, no recovery is attempted, reportFailure fires, reportSuccess does not, and the exception does not escape")
    void whenCandidateScanFails_NoRecoveryAttemptedAndFailureReported() {
        when(paymentRepository.findCreatedWithInitiationEvidenceCandidateIds())
                .thenThrow(new RuntimeException("simulated candidate scan failure"));

        scheduler.recoverInterruptedInitiations(); // must not throw

        verify(paymentCreationOrchestrator, never()).recoverFromInitiationEvidence(anyLong());
        verify(schedulerHealthSignal).reportFailure("PaymentInitiationRecoveryScheduler.recoverInterruptedInitiations");
        verify(schedulerHealthSignal, never()).reportSuccess(anyString());
        verifyNoMoreInteractions(schedulerHealthSignal);
    }

    @Test
    @DisplayName("candidate scan is performed before any recovery attempt")
    void candidateScanIsPerformedBeforeAnyRecoveryAttempt() {
        when(paymentRepository.findCreatedWithInitiationEvidenceCandidateIds()).thenReturn(List.of(1L));
        Payment payment = recoveredPayment();
        when(paymentCreationOrchestrator.recoverFromInitiationEvidence(1L)).thenReturn(payment);

        scheduler.recoverInterruptedInitiations();

        InOrder inOrder = org.mockito.Mockito.inOrder(paymentRepository, paymentCreationOrchestrator);
        inOrder.verify(paymentRepository).findCreatedWithInitiationEvidenceCandidateIds();
        inOrder.verify(paymentCreationOrchestrator).recoverFromInitiationEvidence(1L);
    }
}
