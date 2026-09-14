// diagnostic/commitment/application/SellerDiagnosticCommitmentServiceTest.java
package com.example.autoauction.diagnostic.commitment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.deposit.web.dto.DepositResponse;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.policy.domain.PolicyVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * PHASE 1 (seller commitment redesign): unit-level proof of SellerDiagnosticCommitmentService's
 * orchestration - it must call DepositService for the actual ledger mutation and never
 * duplicate Deposit balance/blockedAmount logic itself, and it must never silently allow a
 * second active commitment for the same diagnostic request.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PHASE 1: SellerDiagnosticCommitmentService")
class SellerDiagnosticCommitmentServiceTest {

    @Mock
    private SellerDiagnosticCommitmentRepository repository;

    @Mock
    private DepositService depositService;

    @Mock
    private PolicyVersionService policyVersionService;

    private SellerDiagnosticCommitmentService service;

    private final Long sellerId = 1L;
    private final Long requestId = 100L;
    private final Long depositId = 5L;
    private final Long policyVersionId = 9L;
    private final BigDecimal amount = new BigDecimal("10000");
    private final BigDecimal feeAmount = new BigDecimal("4000");

    private void createService() {
        service = new SellerDiagnosticCommitmentService(repository, depositService, policyVersionService);
    }

    /**
     * X3-E: freezeCommitment() now resolves and snapshots the currently published
     * PolicyVersion's id - every freezeCommitment test needs this stubbed.
     */
    private void stubCurrentPublishedPolicy() {
        PolicyVersion published = new PolicyVersion(policyVersionId, 1L, new BigDecimal("0.02"), true,
                1L, OffsetDateTime.now(), 1L, OffsetDateTime.now(), null, 0L, 0L);
        when(policyVersionService.getCurrentPublished()).thenReturn(published);
    }

    @Test
    @DisplayName("freezeCommitment: резолвит depositId через DepositService, вызывает freezeForDiagnosticRequest, " +
            "сохраняет commitment с committedAmount и diagnosticFeeAmount, статус FROZEN - без прямой мутации Deposit")
    void freezeCommitment_HappyPath_DelegatesToDepositServiceThenPersistsCommitment() {
        createService();
        stubCurrentPublishedPolicy();
        when(repository.existsByDiagnosticRequestId(requestId)).thenReturn(false);
        DepositResponse depositResponse = new DepositResponse(depositId, sellerId,
                new BigDecimal("50000"), BigDecimal.ZERO, new BigDecimal("50000"), null, OffsetDateTime.now());
        when(depositService.getDepositByUserId(sellerId)).thenReturn(depositResponse);
        when(repository.save(any(SellerDiagnosticCommitment.class))).thenAnswer(i -> i.getArgument(0));

        service.freezeCommitment(sellerId, requestId, amount, feeAmount);

        verify(depositService).freezeForDiagnosticRequest(sellerId, requestId, amount);

        ArgumentCaptor<SellerDiagnosticCommitment> captor = ArgumentCaptor.forClass(SellerDiagnosticCommitment.class);
        verify(repository).save(captor.capture());
        SellerDiagnosticCommitment saved = captor.getValue();
        assertThat(saved.getDiagnosticRequestId()).isEqualTo(requestId);
        assertThat(saved.getDepositId()).isEqualTo(depositId);
        assertThat(saved.getCommittedAmount()).isEqualByComparingTo(amount);
        assertThat(saved.getDiagnosticFeeAmount()).isEqualByComparingTo(feeAmount);
        assertThat(saved.getStatus()).isEqualTo(CommitmentStatus.FROZEN);
        assertThat(saved.getFrozenAt()).isNotNull();
        assertThat(saved.getPolicyVersionId()).isEqualTo(policyVersionId);

        // The service itself never touches Deposit internals directly. diagnosticFeeAmount is a
        // commitment-only snapshot, never passed to DepositService.
        verifyNoMoreInteractions(depositService);
    }

    @Test
    @DisplayName("freezeCommitment: существующий активный commitment для того же request -> " +
            "IllegalStateException, DepositService не вызывается вовсе")
    void freezeCommitment_AlreadyExists_ThrowsBeforeTouchingDeposit() {
        createService();
        when(repository.existsByDiagnosticRequestId(requestId)).thenReturn(true);

        assertThatThrownBy(() -> service.freezeCommitment(sellerId, requestId, amount, feeAmount))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(depositService);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("releaseCommitment: commitment не найден -> безопасный no-op, DepositService не вызывается")
    void releaseCommitment_NotFound_SafeNoOp() {
        createService();
        when(repository.findByDiagnosticRequestId(requestId)).thenReturn(Optional.empty());

        service.releaseCommitment(sellerId, requestId);

        verifyNoInteractions(depositService);
        verify(repository, never()).deleteByDiagnosticRequestId(any());
    }

    @Test
    @DisplayName("releaseCommitment: найден -> вызывает unfreezeForDiagnosticRequest с committedAmount " +
            "из записи, затем удаляет commitment")
    void releaseCommitment_Found_DelegatesThenDeletes() {
        createService();
        SellerDiagnosticCommitment commitment = new SellerDiagnosticCommitment();
        commitment.setDiagnosticRequestId(requestId);
        commitment.setDepositId(depositId);
        commitment.setCommittedAmount(amount);
        commitment.setFrozenAt(OffsetDateTime.now());
        when(repository.findByDiagnosticRequestId(requestId)).thenReturn(Optional.of(commitment));

        service.releaseCommitment(sellerId, requestId);

        verify(depositService).unfreezeForDiagnosticRequest(sellerId, requestId, amount);
        verify(repository).deleteByDiagnosticRequestId(requestId);
    }

    @Test
    @DisplayName("releaseCommitment: сбой в DepositService пробрасывается наружу, commitment НЕ удаляется")
    void releaseCommitment_DepositServiceThrows_PropagatesAndDoesNotDelete() {
        createService();
        SellerDiagnosticCommitment commitment = new SellerDiagnosticCommitment();
        commitment.setDiagnosticRequestId(requestId);
        commitment.setCommittedAmount(amount);
        when(repository.findByDiagnosticRequestId(requestId)).thenReturn(Optional.of(commitment));
        doThrow(new IllegalStateException("DB unavailable"))
                .when(depositService).unfreezeForDiagnosticRequest(sellerId, requestId, amount);

        assertThatThrownBy(() -> service.releaseCommitment(sellerId, requestId))
                .isInstanceOf(IllegalStateException.class);

        verify(repository, never()).deleteByDiagnosticRequestId(any());
    }

    @Test
    @DisplayName("hasActiveCommitment: делегирует existsByDiagnosticRequestId без побочных эффектов")
    void hasActiveCommitment_DelegatesToExistsQuery() {
        createService();
        when(repository.existsByDiagnosticRequestId(requestId)).thenReturn(true);

        boolean result = service.hasActiveCommitment(requestId);

        assertThat(result).isTrue();
        verify(repository).existsByDiagnosticRequestId(requestId);
    }

    // PHASE 2: FROZEN -> AWAITING_FINALIZATION transition

    @Test
    @DisplayName("markAwaitingFinalization: атомарный UPDATE находит FROZEN-строку -> транзитится, " +
            "Deposit не затрагивается вовсе")
    void markAwaitingFinalization_AtomicUpdateMatches_Transitions() {
        createService();
        when(repository.markAwaitingFinalization(requestId)).thenReturn(true);

        service.markAwaitingFinalization(requestId);

        verify(repository).markAwaitingFinalization(requestId);
        verify(repository, never()).findByDiagnosticRequestId(any());
        verifyNoInteractions(depositService);
    }

    @Test
    @DisplayName("markAwaitingFinalization: атомарный UPDATE не находит строку, но commitment уже " +
            "AWAITING_FINALIZATION -> идемпотентный успех, без исключения")
    void markAwaitingFinalization_AlreadyTransitioned_IdempotentSuccess() {
        createService();
        when(repository.markAwaitingFinalization(requestId)).thenReturn(false);
        SellerDiagnosticCommitment current = new SellerDiagnosticCommitment();
        current.setDiagnosticRequestId(requestId);
        current.setStatus(CommitmentStatus.AWAITING_FINALIZATION);
        when(repository.findByDiagnosticRequestId(requestId)).thenReturn(Optional.of(current));

        service.markAwaitingFinalization(requestId);

        verifyNoInteractions(depositService);
    }

    @Test
    @DisplayName("markAwaitingFinalization: commitment не найден вовсе -> IllegalStateException")
    void markAwaitingFinalization_NotFound_Throws() {
        createService();
        when(repository.markAwaitingFinalization(requestId)).thenReturn(false);
        when(repository.findByDiagnosticRequestId(requestId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markAwaitingFinalization(requestId))
                .isInstanceOf(IllegalStateException.class);
    }
}
