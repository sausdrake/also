package com.example.autoauction.auction.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.FinalizationOutcome;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * X3-D: unit coverage for SaleCommitmentResolutionOrchestrator's eligibility gate, fee/refund
 * math, claim-first finalization, and idempotency matrix.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("X3-D: SaleCommitmentResolutionOrchestrator")
class SaleCommitmentResolutionOrchestratorTest {

    @Mock
    private SellerDiagnosticCommitmentRepository commitmentRepository;
    @Mock
    private DepositService depositService;

    private SaleCommitmentResolutionOrchestrator orchestrator;

    private static final Long COMMITMENT_ID = 500L;
    private static final Long DIAGNOSTIC_REQUEST_ID = 50L;
    private static final Long DEPOSIT_ID = 7L;

    private SellerDiagnosticCommitment commitment;

    @BeforeEach
    void setUp() {
        orchestrator = new SaleCommitmentResolutionOrchestrator(commitmentRepository, depositService);

        commitment = new SellerDiagnosticCommitment();
        commitment.setId(COMMITMENT_ID);
        commitment.setDiagnosticRequestId(DIAGNOSTIC_REQUEST_ID);
        commitment.setDepositId(DEPOSIT_ID);
        commitment.setCommittedAmount(new BigDecimal("10000"));
        commitment.setDiagnosticFeeAmount(new BigDecimal("4000"));
        commitment.setStatus(CommitmentStatus.AWAITING_FINALIZATION);
    }

    @Nested
    @DisplayName("Outcome A (SUCCESS/BUYER_FAULT/PLATFORM_FAULT all map here)")
    class OutcomeATests {

        @Test
        @DisplayName("A: penalty=0, fee=0, полный refund; claim-first затем ledger")
        void resolve_OutcomeA_ZeroFeeZeroPenaltyFullRefund() {
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));
            when(commitmentRepository.finalize(eq(DIAGNOSTIC_REQUEST_ID), eq(FinalizationOutcome.A),
                    eq(BigDecimal.ZERO), eq(new BigDecimal("10000")), any())).thenReturn(true);

            orchestrator.resolve(COMMITMENT_ID, FinalizationOutcome.A);

            InOrder inOrder = inOrder(commitmentRepository, depositService);
            inOrder.verify(commitmentRepository).finalize(eq(DIAGNOSTIC_REQUEST_ID), eq(FinalizationOutcome.A),
                    eq(BigDecimal.ZERO), eq(new BigDecimal("10000")), any());
            inOrder.verify(depositService).chargeForDiagnosticRequest(DEPOSIT_ID, DIAGNOSTIC_REQUEST_ID,
                    new BigDecimal("10000"), BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("Outcome B")
    class OutcomeBTests {

        @Test
        @DisplayName("B: fee=diagnosticFeeAmount, penalty=0, refund=committed-fee")
        void resolve_OutcomeB_FeeChargedRemainderRefunded() {
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));
            when(commitmentRepository.finalize(eq(DIAGNOSTIC_REQUEST_ID), eq(FinalizationOutcome.B),
                    eq(BigDecimal.ZERO), eq(new BigDecimal("6000")), any())).thenReturn(true);

            orchestrator.resolve(COMMITMENT_ID, FinalizationOutcome.B);

            verify(commitmentRepository).finalize(eq(DIAGNOSTIC_REQUEST_ID), eq(FinalizationOutcome.B),
                    eq(BigDecimal.ZERO), eq(new BigDecimal("6000")), any());
            verify(depositService).chargeForDiagnosticRequest(DEPOSIT_ID, DIAGNOSTIC_REQUEST_ID,
                    new BigDecimal("10000"), new BigDecimal("4000"));
        }

        @Test
        @DisplayName("X3-D corrective (C1): diagnosticFeeAmount > committedAmount -> IllegalStateException, no claim, no ledger mutation")
        void resolve_OutcomeB_FeeExceedsCommittedAmount_FailsClosedBeforeClaim() {
            commitment.setDiagnosticFeeAmount(new BigDecimal("15000")); // > committedAmount (10000)
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));

            assertThatThrownBy(() -> orchestrator.resolve(COMMITMENT_ID, FinalizationOutcome.B))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exceeding committedAmount");

            verify(commitmentRepository, never()).finalize(any(), any(), any(), any(), any());
            verifyNoInteractions(depositService);
        }
    }

    @Nested
    @DisplayName("Eligibility / fail-closed")
    class EligibilityTests {

        @Test
        @DisplayName("Commitment не найден -> IllegalStateException, ledger не тронут")
        void resolve_CommitmentNotFound_Rejects() {
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> orchestrator.resolve(COMMITMENT_ID, FinalizationOutcome.A))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(depositService);
            verify(commitmentRepository, never()).finalize(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Commitment FROZEN -> IllegalStateException, ledger не тронут")
        void resolve_CommitmentFrozen_Rejects() {
            commitment.setStatus(CommitmentStatus.FROZEN);
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));

            assertThatThrownBy(() -> orchestrator.resolve(COMMITMENT_ID, FinalizationOutcome.A))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(depositService);
            verify(commitmentRepository, never()).finalize(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Claim проигран (0 строк), текущее состояние - неожиданный статус -> IllegalStateException")
        void resolve_ClaimLostUnexpectedStatus_Rejects() {
            when(commitmentRepository.findById(COMMITMENT_ID))
                    .thenReturn(Optional.of(commitment))
                    .thenReturn(Optional.of(commitment)); // re-read after lost claim: still AWAITING_FINALIZATION somehow
            when(commitmentRepository.finalize(any(), any(), any(), any(), any())).thenReturn(false);

            assertThatThrownBy(() -> orchestrator.resolve(COMMITMENT_ID, FinalizationOutcome.A))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(depositService);
        }
    }

    @Nested
    @DisplayName("Idempotency")
    class IdempotencyTests {

        @Test
        @DisplayName("Same-outcome retry: уже FINALIZED с тем же outcome -> безопасный no-op, ledger не тронут")
        void resolve_AlreadyFinalizedSameOutcome_SafeNoOp() {
            commitment.setStatus(CommitmentStatus.FINALIZED);
            commitment.setFinalizationOutcome(FinalizationOutcome.A);
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));

            orchestrator.resolve(COMMITMENT_ID, FinalizationOutcome.A);

            verifyNoInteractions(depositService);
            verify(commitmentRepository, never()).finalize(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Conflicting outcome: уже FINALIZED с ДРУГИМ outcome -> fail closed, ledger не тронут")
        void resolve_AlreadyFinalizedConflictingOutcome_FailsClosed() {
            commitment.setStatus(CommitmentStatus.FINALIZED);
            commitment.setFinalizationOutcome(FinalizationOutcome.B);
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));

            assertThatThrownBy(() -> orchestrator.resolve(COMMITMENT_ID, FinalizationOutcome.A))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(depositService);
            verify(commitmentRepository, never()).finalize(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Race lost на claim, но проигранный статус совпадает с запрошенным outcome -> безопасный no-op")
        void resolve_LostRaceButSameOutcome_SafeNoOp() {
            SellerDiagnosticCommitment afterRace = new SellerDiagnosticCommitment();
            afterRace.setId(COMMITMENT_ID);
            afterRace.setDiagnosticRequestId(DIAGNOSTIC_REQUEST_ID);
            afterRace.setDepositId(DEPOSIT_ID);
            afterRace.setCommittedAmount(new BigDecimal("10000"));
            afterRace.setDiagnosticFeeAmount(new BigDecimal("4000"));
            afterRace.setStatus(CommitmentStatus.FINALIZED);
            afterRace.setFinalizationOutcome(FinalizationOutcome.A);

            when(commitmentRepository.findById(COMMITMENT_ID))
                    .thenReturn(Optional.of(commitment))
                    .thenReturn(Optional.of(afterRace));
            when(commitmentRepository.finalize(any(), any(), any(), any(), any())).thenReturn(false);

            orchestrator.resolve(COMMITMENT_ID, FinalizationOutcome.A);

            verifyNoInteractions(depositService);
        }
    }
}
