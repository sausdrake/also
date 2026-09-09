// diagnostic/commitment/application/SellerDiagnosticCommitmentFinalizationServiceTest.java
package com.example.autoauction.diagnostic.commitment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.FinalizationOutcome;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRejectionCategory;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PHASE 3B: unit-level proof of SellerDiagnosticCommitmentFinalizationService's own
 * orchestration logic - claim-first ordering, idempotency, fail-closed snapshot validation,
 * and that it never calls SystemSettingsService or computes anything beyond Outcome B.
 *
 * PHASE 3B corrective slice: also proves the eligibility guard added to support the new
 * manual retry endpoint - rejectionCategory is checked FIRST, before the commitment is even
 * loaded, and only VEHICLE_CONDITION passes.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PHASE 3B: SellerDiagnosticCommitmentFinalizationService")
class SellerDiagnosticCommitmentFinalizationServiceTest {

    @Mock
    private SellerDiagnosticCommitmentRepository repository;

    @Mock
    private DepositService depositService;

    @Mock
    private DiagnosticRequestRepository diagnosticRequestRepository;

    private SellerDiagnosticCommitmentFinalizationService service;

    private final Long diagnosticRequestId = 100L;
    private final Long depositId = 5L;

    @BeforeEach
    void createService() {
        service = new SellerDiagnosticCommitmentFinalizationService(repository, depositService, diagnosticRequestRepository);
    }

    private SellerDiagnosticCommitment commitment(BigDecimal committed, BigDecimal fee, CommitmentStatus status) {
        SellerDiagnosticCommitment c = new SellerDiagnosticCommitment();
        c.setDiagnosticRequestId(diagnosticRequestId);
        c.setDepositId(depositId);
        c.setCommittedAmount(committed);
        c.setDiagnosticFeeAmount(fee);
        c.setStatus(status);
        c.setFrozenAt(OffsetDateTime.now());
        return c;
    }

    private DiagnosticRequest requestWithCategory(DiagnosticRejectionCategory category) {
        DiagnosticRequest r = new DiagnosticRequest();
        r.setId(diagnosticRequestId);
        r.setRejectionCategory(category);
        return r;
    }

    /** Stubs the eligibility read for every "past the guard" test - VEHICLE_CONDITION. */
    private void stubEligible() {
        when(diagnosticRequestRepository.findById(diagnosticRequestId))
                .thenReturn(Optional.of(requestWithCategory(DiagnosticRejectionCategory.VEHICLE_CONDITION)));
    }

    @Nested
    @DisplayName("Eligibility guard (PHASE 3B corrective slice)")
    class EligibilityTests {

        @Test
        @DisplayName("VEHICLE_CONDITION — проходит guard, доходит до claim")
        void vehicleCondition_PassesGuard() {
            stubEligible();
            SellerDiagnosticCommitment c = commitment(new BigDecimal("10000"), new BigDecimal("4000"), CommitmentStatus.AWAITING_FINALIZATION);
            when(repository.findByDiagnosticRequestId(diagnosticRequestId)).thenReturn(Optional.of(c));
            when(repository.finalize(any(), any(), any(), any(), any())).thenReturn(true);

            service.finalizeAsOutcomeB(diagnosticRequestId);

            verify(repository).finalize(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("SELLER_VIOLATION — IllegalStateException, ни claim, ни ledger, ни чтение commitment не выполняются")
        void sellerViolation_RejectedBeforeAnyCommitmentAccess() {
            when(diagnosticRequestRepository.findById(diagnosticRequestId))
                    .thenReturn(Optional.of(requestWithCategory(DiagnosticRejectionCategory.SELLER_VIOLATION)));

            assertThatThrownBy(() -> service.finalizeAsOutcomeB(diagnosticRequestId))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(repository);
            verifyNoInteractions(depositService);
        }

        @Test
        @DisplayName("rejectionCategory == null (например, заявка ещё не отклонена/отозвана) — IllegalStateException")
        void nullCategory_Rejected() {
            when(diagnosticRequestRepository.findById(diagnosticRequestId))
                    .thenReturn(Optional.of(requestWithCategory(null)));

            assertThatThrownBy(() -> service.finalizeAsOutcomeB(diagnosticRequestId))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(repository);
            verifyNoInteractions(depositService);
        }

        @Test
        @DisplayName("DiagnosticRequest не найден — IllegalArgumentException, ни claim, ни ledger")
        void requestNotFound_ThrowsIllegalArgumentException() {
            when(diagnosticRequestRepository.findById(diagnosticRequestId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.finalizeAsOutcomeB(diagnosticRequestId))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(repository);
            verifyNoInteractions(depositService);
        }
    }

    @Test
    @DisplayName("Happy path: claim first, затем ledger charge с exact committed/fee, penalty=0, refund=committed-fee")
    void finalizeAsOutcomeB_HappyPath_ClaimsThenCharges() {
        stubEligible();
        SellerDiagnosticCommitment c = commitment(new BigDecimal("10000"), new BigDecimal("4000"), CommitmentStatus.AWAITING_FINALIZATION);
        when(repository.findByDiagnosticRequestId(diagnosticRequestId)).thenReturn(Optional.of(c));
        when(repository.finalize(eq(diagnosticRequestId), eq(FinalizationOutcome.B), any(), any(), any()))
                .thenReturn(true);

        service.finalizeAsOutcomeB(diagnosticRequestId);

        ArgumentCaptor<BigDecimal> penaltyCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> refundCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(repository).finalize(eq(diagnosticRequestId), eq(FinalizationOutcome.B),
                penaltyCaptor.capture(), refundCaptor.capture(), any(OffsetDateTime.class));
        assertThat(penaltyCaptor.getValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(refundCaptor.getValue()).isEqualByComparingTo("6000");

        verify(depositService).chargeForDiagnosticRequest(depositId, diagnosticRequestId,
                new BigDecimal("10000"), new BigDecimal("4000"));

        // Claim happens before the ledger call - verified by ordering.
        var inOrder = inOrder(repository, depositService);
        inOrder.verify(repository).finalize(any(), any(), any(), any(), any());
        inOrder.verify(depositService).chargeForDiagnosticRequest(any(), any(), any(), any());
    }

    @Test
    @DisplayName("fee == commitment: refund=0, всё равно передаётся в finalize и в charge")
    void finalizeAsOutcomeB_FeeEqualsCommitment() {
        stubEligible();
        SellerDiagnosticCommitment c = commitment(new BigDecimal("10000"), new BigDecimal("10000"), CommitmentStatus.AWAITING_FINALIZATION);
        when(repository.findByDiagnosticRequestId(diagnosticRequestId)).thenReturn(Optional.of(c));
        when(repository.finalize(any(), any(), any(), any(), any())).thenReturn(true);

        service.finalizeAsOutcomeB(diagnosticRequestId);

        ArgumentCaptor<BigDecimal> refundCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(repository).finalize(any(), any(), any(), refundCaptor.capture(), any());
        assertThat(refundCaptor.getValue()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(depositService).chargeForDiagnosticRequest(depositId, diagnosticRequestId,
                new BigDecimal("10000"), new BigDecimal("10000"));
    }

    @Test
    @DisplayName("fee == 0: penalty=0, refund=committed, charge вызывается с chargeAmount=0")
    void finalizeAsOutcomeB_ZeroFee() {
        stubEligible();
        SellerDiagnosticCommitment c = commitment(new BigDecimal("10000"), BigDecimal.ZERO, CommitmentStatus.AWAITING_FINALIZATION);
        when(repository.findByDiagnosticRequestId(diagnosticRequestId)).thenReturn(Optional.of(c));
        when(repository.finalize(any(), any(), any(), any(), any())).thenReturn(true);

        service.finalizeAsOutcomeB(diagnosticRequestId);

        verify(depositService).chargeForDiagnosticRequest(depositId, diagnosticRequestId,
                new BigDecimal("10000"), BigDecimal.ZERO);
    }

    @Test
    @DisplayName("diagnosticFeeAmount > committedAmount (defensive) — IllegalStateException до claim и до ledger")
    void finalizeAsOutcomeB_FeeExceedsCommitted_ThrowsBeforeClaim() {
        stubEligible();
        SellerDiagnosticCommitment c = commitment(new BigDecimal("10000"), new BigDecimal("10001"), CommitmentStatus.AWAITING_FINALIZATION);
        when(repository.findByDiagnosticRequestId(diagnosticRequestId)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.finalizeAsOutcomeB(diagnosticRequestId))
                .isInstanceOf(IllegalStateException.class);

        verify(repository, never()).finalize(any(), any(), any(), any(), any());
        verifyNoInteractions(depositService);
    }

    @Test
    @DisplayName("Claim проигран (0 rows), но уже FINALIZED — идемпотентный успех, ledger не трогается")
    void finalizeAsOutcomeB_AlreadyFinalized_IdempotentNoOp() {
        stubEligible();
        SellerDiagnosticCommitment c = commitment(new BigDecimal("10000"), new BigDecimal("4000"), CommitmentStatus.AWAITING_FINALIZATION);
        when(repository.finalize(any(), any(), any(), any(), any())).thenReturn(false);

        SellerDiagnosticCommitment finalized = commitment(new BigDecimal("10000"), new BigDecimal("4000"), CommitmentStatus.FINALIZED);
        when(repository.findByDiagnosticRequestId(diagnosticRequestId))
                .thenReturn(Optional.of(c))
                .thenReturn(Optional.of(finalized));

        service.finalizeAsOutcomeB(diagnosticRequestId);

        verifyNoInteractions(depositService);
    }

    @Test
    @DisplayName("Claim проигран (0 rows), статус неожиданный (не FINALIZED) — IllegalStateException")
    void finalizeAsOutcomeB_ClaimLost_UnexpectedStatus_Throws() {
        stubEligible();
        SellerDiagnosticCommitment c = commitment(new BigDecimal("10000"), new BigDecimal("4000"), CommitmentStatus.AWAITING_FINALIZATION);
        SellerDiagnosticCommitment stillFrozen = commitment(new BigDecimal("10000"), new BigDecimal("4000"), CommitmentStatus.FROZEN);
        when(repository.findByDiagnosticRequestId(diagnosticRequestId))
                .thenReturn(Optional.of(c))
                .thenReturn(Optional.of(stillFrozen));
        when(repository.finalize(any(), any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.finalizeAsOutcomeB(diagnosticRequestId))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(depositService);
    }

    @Test
    @DisplayName("Commitment не найден (но заявка eligible) — IllegalStateException, без claim и без ledger")
    void finalizeAsOutcomeB_CommitmentNotFound_Throws() {
        stubEligible();
        when(repository.findByDiagnosticRequestId(diagnosticRequestId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.finalizeAsOutcomeB(diagnosticRequestId))
                .isInstanceOf(IllegalStateException.class);

        verify(repository, never()).finalize(any(), any(), any(), any(), any());
        verifyNoInteractions(depositService);
    }

    @Test
    @DisplayName("Ledger mutation бросает исключение — пробрасывается наружу, не глотается")
    void finalizeAsOutcomeB_LedgerThrows_Propagates() {
        stubEligible();
        SellerDiagnosticCommitment c = commitment(new BigDecimal("10000"), new BigDecimal("4000"), CommitmentStatus.AWAITING_FINALIZATION);
        when(repository.findByDiagnosticRequestId(diagnosticRequestId)).thenReturn(Optional.of(c));
        when(repository.finalize(any(), any(), any(), any(), any())).thenReturn(true);
        doThrow(new IllegalStateException("ledger boom"))
                .when(depositService).chargeForDiagnosticRequest(any(), any(), any(), any());

        assertThatThrownBy(() -> service.finalizeAsOutcomeB(diagnosticRequestId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ledger boom");
    }
}
