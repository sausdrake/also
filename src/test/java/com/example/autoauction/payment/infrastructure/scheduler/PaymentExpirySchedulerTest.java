package com.example.autoauction.payment.infrastructure.scheduler;

import com.example.autoauction.admin.application.SchedulerHealthSignal;
import com.example.autoauction.payment.application.PaymentExpiryService;
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
 * Phase 79 (G-77-4): PaymentExpiryScheduler is a Family-B two-phase scheduler (candidate-scan
 * then per-candidate loop), structurally distinct from {@link PaymentConfirmationScheduler} in
 * that {@code expireOnePayment} returns a boolean the scheduler inspects (unlike
 * {@code confirmPayment}, whose return value is ignored). Actual expiry state transitions,
 * late-success interaction and transaction boundaries remain proven by
 * {@code PaymentExpiryServiceIntegrationTest} - not re-asserted here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentExpiryScheduler")
class PaymentExpirySchedulerTest {

    @Mock
    private PaymentExpiryService paymentExpiryService;

    @Mock
    private SchedulerHealthSignal schedulerHealthSignal;

    @InjectMocks
    private PaymentExpiryScheduler scheduler;

    @Test
    @DisplayName("given zero candidates, no payment is expired and the sweep still reports success")
    void givenZeroCandidates_NoPaymentExpiredAndSweepSucceeds() {
        when(paymentExpiryService.findExpiredCandidateIds()).thenReturn(List.of());

        scheduler.sweepExpiredPayments();

        verify(paymentExpiryService, never()).expireOnePayment(anyLong());
        verify(schedulerHealthSignal).reportSuccess("PaymentExpiryScheduler.sweepExpiredPayments");
        verify(schedulerHealthSignal, never()).reportFailure(anyString());
    }

    @Test
    @DisplayName("given multiple candidates, every candidate is offered for expiry")
    void givenMultipleCandidates_EveryCandidateIsOfferedForExpiry() {
        when(paymentExpiryService.findExpiredCandidateIds()).thenReturn(List.of(1L, 2L, 3L));
        when(paymentExpiryService.expireOnePayment(anyLong())).thenReturn(true);

        scheduler.sweepExpiredPayments();

        verify(paymentExpiryService).expireOnePayment(1L);
        verify(paymentExpiryService).expireOnePayment(2L);
        verify(paymentExpiryService).expireOnePayment(3L);
        verify(schedulerHealthSignal).reportSuccess("PaymentExpiryScheduler.sweepExpiredPayments");
    }

    @Test
    @DisplayName("when one candidate's expiry fails, later candidates are still processed and the sweep still reports success")
    void whenOneCandidateFails_LaterCandidatesStillProcessedAndSweepStillSucceeds() {
        when(paymentExpiryService.findExpiredCandidateIds()).thenReturn(List.of(1L, 2L));
        when(paymentExpiryService.expireOnePayment(1L))
                .thenThrow(new RuntimeException("simulated expiry failure"));
        when(paymentExpiryService.expireOnePayment(2L)).thenReturn(true);

        scheduler.sweepExpiredPayments(); // must not throw

        verify(paymentExpiryService).expireOnePayment(1L);
        verify(paymentExpiryService).expireOnePayment(2L);
        verify(schedulerHealthSignal).reportSuccess("PaymentExpiryScheduler.sweepExpiredPayments");
        verify(schedulerHealthSignal, never()).reportFailure(anyString());
    }

    @Test
    @DisplayName("a candidate for which expireOnePayment returns false (not yet actually expired) does not stop the sweep")
    void candidateNotActuallyExpired_DoesNotStopSweep() {
        when(paymentExpiryService.findExpiredCandidateIds()).thenReturn(List.of(1L, 2L));
        when(paymentExpiryService.expireOnePayment(1L)).thenReturn(false);
        when(paymentExpiryService.expireOnePayment(2L)).thenReturn(true);

        scheduler.sweepExpiredPayments();

        verify(paymentExpiryService).expireOnePayment(1L);
        verify(paymentExpiryService).expireOnePayment(2L);
        verify(schedulerHealthSignal).reportSuccess("PaymentExpiryScheduler.sweepExpiredPayments");
    }

    @Test
    @DisplayName("when the candidate scan itself fails, no expiry is attempted, reportFailure fires, reportSuccess does not, and the exception does not escape")
    void whenCandidateScanFails_NoExpiryAttemptedAndFailureReported() {
        when(paymentExpiryService.findExpiredCandidateIds())
                .thenThrow(new RuntimeException("simulated candidate scan failure"));

        scheduler.sweepExpiredPayments(); // must not throw

        verify(paymentExpiryService, never()).expireOnePayment(anyLong());
        verify(schedulerHealthSignal).reportFailure("PaymentExpiryScheduler.sweepExpiredPayments");
        verify(schedulerHealthSignal, never()).reportSuccess(anyString());
        verifyNoMoreInteractions(schedulerHealthSignal);
    }

    @Test
    @DisplayName("candidate scan is performed before any expiry attempt")
    void candidateScanIsPerformedBeforeAnyExpiryAttempt() {
        when(paymentExpiryService.findExpiredCandidateIds()).thenReturn(List.of(1L));
        when(paymentExpiryService.expireOnePayment(1L)).thenReturn(true);

        scheduler.sweepExpiredPayments();

        InOrder inOrder = org.mockito.Mockito.inOrder(paymentExpiryService);
        inOrder.verify(paymentExpiryService).findExpiredCandidateIds();
        inOrder.verify(paymentExpiryService).expireOnePayment(1L);
    }
}
