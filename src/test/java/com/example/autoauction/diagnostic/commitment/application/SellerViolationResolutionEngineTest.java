package com.example.autoauction.diagnostic.commitment.application;

import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.commitment.domain.CommitmentStatus;
import com.example.autoauction.diagnostic.commitment.domain.FinalizationOutcome;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.SellerViolationResolution;
import com.example.autoauction.diagnostic.commitment.domain.SellerViolationResolutionItem;
import com.example.autoauction.diagnostic.commitment.domain.TriggerSource;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerViolationResolutionRepository;
import com.example.autoauction.policy.domain.PenaltyType;
import com.example.autoauction.policy.domain.PolicyVersionViolation;
import com.example.autoauction.policy.domain.ViolationDefinition;
import com.example.autoauction.policy.domain.port.PolicyVersionViolationRepository;
import com.example.autoauction.policy.domain.port.ViolationDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * X3-E: unit coverage for SellerViolationResolutionEngine - the ONE shared calculation/claim/
 * charge engine for both triggers (SALE_FAILED_C and DIAGNOSTIC_REJECTION).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("X3-E: SellerViolationResolutionEngine")
class SellerViolationResolutionEngineTest {

    @Mock private SellerDiagnosticCommitmentRepository commitmentRepository;
    @Mock private ViolationDefinitionRepository violationDefinitionRepository;
    @Mock private PolicyVersionViolationRepository policyVersionViolationRepository;
    @Mock private SellerViolationResolutionRepository sellerViolationResolutionRepository;
    @Mock private DepositService depositService;

    private SellerViolationResolutionEngine engine;

    private static final Long COMMITMENT_ID = 500L;
    private static final Long DIAGNOSTIC_REQUEST_ID = 50L;
    private static final Long DEPOSIT_ID = 7L;
    private static final Long POLICY_VERSION_ID = 42L;
    private static final Long AUCTION_ID = 900L;
    private static final Long RESOLVED_BY = 1L;
    private static final Long VIOLATION_A_ID = 1L;
    private static final Long VIOLATION_B_ID = 2L;

    private SellerDiagnosticCommitment commitment;
    private ViolationDefinition violationA;
    private ViolationDefinition violationB;

    @BeforeEach
    void setUp() {
        engine = new SellerViolationResolutionEngine(commitmentRepository, violationDefinitionRepository,
                policyVersionViolationRepository, sellerViolationResolutionRepository, depositService);

        commitment = new SellerDiagnosticCommitment();
        commitment.setId(COMMITMENT_ID);
        commitment.setDiagnosticRequestId(DIAGNOSTIC_REQUEST_ID);
        commitment.setDepositId(DEPOSIT_ID);
        commitment.setCommittedAmount(new BigDecimal("50000"));
        commitment.setDiagnosticFeeAmount(new BigDecimal("4000"));
        commitment.setStatus(CommitmentStatus.AWAITING_FINALIZATION);

        violationA = new ViolationDefinition(VIOLATION_A_ID, "SELLER_REFUSED_HANDOVER", "desc", OffsetDateTime.now());
        violationB = new ViolationDefinition(VIOLATION_B_ID, "SELLER_UNREACHABLE", "desc", OffsetDateTime.now());
    }

    private PolicyVersionViolation percentageConfig(Long violationId, String percent) {
        return new PolicyVersionViolation(POLICY_VERSION_ID, violationId, PenaltyType.PERCENTAGE,
                new BigDecimal(percent), OffsetDateTime.now());
    }

    @Nested
    @DisplayName("Calculation")
    class CalculationTests {

        @Test
        @DisplayName("Single PERCENTAGE violation: penalty rounds HALF_UP scale 2, refund = commitment - penalty")
        void resolve_SinglePercentageViolation_CalculatesAndClaims() {
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));
            when(violationDefinitionRepository.findById(VIOLATION_A_ID)).thenReturn(Optional.of(violationA));
            when(policyVersionViolationRepository.findByPolicyVersionIdAndViolationDefinitionId(POLICY_VERSION_ID, VIOLATION_A_ID))
                    .thenReturn(Optional.of(percentageConfig(VIOLATION_A_ID, "20")));
            when(commitmentRepository.finalize(eq(DIAGNOSTIC_REQUEST_ID), eq(FinalizationOutcome.C),
                    eq(new BigDecimal("10000.00")), eq(new BigDecimal("40000.00")), any())).thenReturn(true);

            engine.resolve(COMMITMENT_ID, POLICY_VERSION_ID, List.of(VIOLATION_A_ID), RESOLVED_BY,
                    TriggerSource.SALE_FAILED_C, AUCTION_ID);

            InOrder inOrder = inOrder(commitmentRepository, sellerViolationResolutionRepository, depositService);
            inOrder.verify(commitmentRepository).finalize(eq(DIAGNOSTIC_REQUEST_ID), eq(FinalizationOutcome.C),
                    eq(new BigDecimal("10000.00")), eq(new BigDecimal("40000.00")), any());
            inOrder.verify(sellerViolationResolutionRepository).saveWithItems(any(), anyList());
            inOrder.verify(depositService).chargeSellerViolationPenalty(DEPOSIT_ID, DIAGNOSTIC_REQUEST_ID, AUCTION_ID,
                    new BigDecimal("50000"), new BigDecimal("10000.00"));
        }

        @Test
        @DisplayName("Additive multi-violation, no cap triggered: raw = sum, applied = raw")
        void resolve_MultipleViolationsNoCap_Additive() {
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));
            when(violationDefinitionRepository.findById(VIOLATION_A_ID)).thenReturn(Optional.of(violationA));
            when(violationDefinitionRepository.findById(VIOLATION_B_ID)).thenReturn(Optional.of(violationB));
            when(policyVersionViolationRepository.findByPolicyVersionIdAndViolationDefinitionId(POLICY_VERSION_ID, VIOLATION_A_ID))
                    .thenReturn(Optional.of(percentageConfig(VIOLATION_A_ID, "20")));
            when(policyVersionViolationRepository.findByPolicyVersionIdAndViolationDefinitionId(POLICY_VERSION_ID, VIOLATION_B_ID))
                    .thenReturn(Optional.of(percentageConfig(VIOLATION_B_ID, "30")));
            // 20% + 30% of 50000 = 10000 + 15000 = 25000, well under the 50000 cap.
            when(commitmentRepository.finalize(eq(DIAGNOSTIC_REQUEST_ID), eq(FinalizationOutcome.C),
                    eq(new BigDecimal("25000.00")), eq(new BigDecimal("25000.00")), any())).thenReturn(true);

            engine.resolve(COMMITMENT_ID, POLICY_VERSION_ID, List.of(VIOLATION_A_ID, VIOLATION_B_ID), RESOLVED_BY,
                    TriggerSource.SALE_FAILED_C, AUCTION_ID);

            verify(commitmentRepository).finalize(eq(DIAGNOSTIC_REQUEST_ID), eq(FinalizationOutcome.C),
                    eq(new BigDecimal("25000.00")), eq(new BigDecimal("25000.00")), any());
        }

        @Test
        @DisplayName("Cap triggered: applied capped at frozenCommitment, refund = 0, raw kept in audit items uncapped")
        void resolve_RawExceedsCommitment_AppliedCappedRefundZero() {
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));
            when(violationDefinitionRepository.findById(VIOLATION_A_ID)).thenReturn(Optional.of(violationA));
            when(violationDefinitionRepository.findById(VIOLATION_B_ID)).thenReturn(Optional.of(violationB));
            when(policyVersionViolationRepository.findByPolicyVersionIdAndViolationDefinitionId(POLICY_VERSION_ID, VIOLATION_A_ID))
                    .thenReturn(Optional.of(percentageConfig(VIOLATION_A_ID, "80")));
            when(policyVersionViolationRepository.findByPolicyVersionIdAndViolationDefinitionId(POLICY_VERSION_ID, VIOLATION_B_ID))
                    .thenReturn(Optional.of(percentageConfig(VIOLATION_B_ID, "50")));
            // 80% + 50% of 50000 = 40000 + 25000 = 65000 raw, capped to 50000, refund 0.
            when(commitmentRepository.finalize(eq(DIAGNOSTIC_REQUEST_ID), eq(FinalizationOutcome.C),
                    eq(new BigDecimal("50000.00")), eq(new BigDecimal("0.00")), any())).thenReturn(true);

            engine.resolve(COMMITMENT_ID, POLICY_VERSION_ID, List.of(VIOLATION_A_ID, VIOLATION_B_ID), RESOLVED_BY,
                    TriggerSource.SALE_FAILED_C, AUCTION_ID);

            verify(commitmentRepository).finalize(eq(DIAGNOSTIC_REQUEST_ID), eq(FinalizationOutcome.C),
                    eq(new BigDecimal("50000.00")), eq(new BigDecimal("0.00")), any());

            @SuppressWarnings("unchecked")
            var itemsCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(sellerViolationResolutionRepository).saveWithItems(any(), itemsCaptor.capture());
            List<SellerViolationResolutionItem> items = itemsCaptor.getValue();
            assertThat(items).hasSize(2);
            // Each item keeps its own UNCAPPED calculated amount - the cap is never allocated
            // back to individual violations.
            assertThat(items).extracting(SellerViolationResolutionItem::getCalculatedPenaltyAmount)
                    .containsExactlyInAnyOrder(new BigDecimal("40000.00"), new BigDecimal("25000.00"));
        }

        @Test
        @DisplayName("FIXED_AMOUNT penalty used as-is (no percentage math)")
        void resolve_FixedAmountViolation_UsesConfiguredValueDirectly() {
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));
            when(violationDefinitionRepository.findById(VIOLATION_A_ID)).thenReturn(Optional.of(violationA));
            PolicyVersionViolation fixedConfig = new PolicyVersionViolation(POLICY_VERSION_ID, VIOLATION_A_ID,
                    PenaltyType.FIXED_AMOUNT, new BigDecimal("1234.50"), OffsetDateTime.now());
            when(policyVersionViolationRepository.findByPolicyVersionIdAndViolationDefinitionId(POLICY_VERSION_ID, VIOLATION_A_ID))
                    .thenReturn(Optional.of(fixedConfig));
            when(commitmentRepository.finalize(eq(DIAGNOSTIC_REQUEST_ID), eq(FinalizationOutcome.C),
                    eq(new BigDecimal("1234.50")), eq(new BigDecimal("48765.50")), any())).thenReturn(true);

            engine.resolve(COMMITMENT_ID, POLICY_VERSION_ID, List.of(VIOLATION_A_ID), RESOLVED_BY,
                    TriggerSource.SALE_FAILED_C, AUCTION_ID);

            verify(commitmentRepository).finalize(eq(DIAGNOSTIC_REQUEST_ID), eq(FinalizationOutcome.C),
                    eq(new BigDecimal("1234.50")), eq(new BigDecimal("48765.50")), any());
        }
    }

    @Nested
    @DisplayName("Validation / fail-closed")
    class ValidationTests {

        @Test
        @DisplayName("violationDefinitionIds null -> IllegalArgumentException, no claim, no ledger")
        void resolve_NullViolationIds_Rejects() {
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));

            assertThatThrownBy(() -> engine.resolve(COMMITMENT_ID, POLICY_VERSION_ID, null, RESOLVED_BY,
                    TriggerSource.SALE_FAILED_C, AUCTION_ID))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(commitmentRepository, never()).finalize(any(), any(), any(), any(), any());
            verifyNoInteractions(depositService, sellerViolationResolutionRepository);
        }

        @Test
        @DisplayName("violationDefinitionIds empty -> IllegalArgumentException")
        void resolve_EmptyViolationIds_Rejects() {
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));

            assertThatThrownBy(() -> engine.resolve(COMMITMENT_ID, POLICY_VERSION_ID, List.of(), RESOLVED_BY,
                    TriggerSource.SALE_FAILED_C, AUCTION_ID))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(depositService);
        }

        @Test
        @DisplayName("Duplicate violation id in the request -> IllegalArgumentException before any policy lookup")
        void resolve_DuplicateViolationIdInRequest_Rejects() {
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));

            assertThatThrownBy(() -> engine.resolve(COMMITMENT_ID, POLICY_VERSION_ID,
                    List.of(VIOLATION_A_ID, VIOLATION_A_ID), RESOLVED_BY, TriggerSource.SALE_FAILED_C, AUCTION_ID))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(policyVersionViolationRepository, depositService);
        }

        @Test
        @DisplayName("Unknown violation id -> IllegalArgumentException")
        void resolve_UnknownViolationId_Rejects() {
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));
            when(violationDefinitionRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> engine.resolve(COMMITMENT_ID, POLICY_VERSION_ID, List.of(999L), RESOLVED_BY,
                    TriggerSource.SALE_FAILED_C, AUCTION_ID))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(depositService);
        }

        @Test
        @DisplayName("Financially inert violation (VEHICLE_CONDITION_CHANGED_AFTER_DIAGNOSTIC) -> fails closed, never applied")
        void resolve_FinanciallyInertViolation_FailsClosed() {
            ViolationDefinition inert = new ViolationDefinition(3L, "VEHICLE_CONDITION_CHANGED_AFTER_DIAGNOSTIC",
                    "desc", OffsetDateTime.now());
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));
            when(violationDefinitionRepository.findById(3L)).thenReturn(Optional.of(inert));

            assertThatThrownBy(() -> engine.resolve(COMMITMENT_ID, POLICY_VERSION_ID, List.of(3L), RESOLVED_BY,
                    TriggerSource.SALE_FAILED_C, AUCTION_ID))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(policyVersionViolationRepository, depositService);
            verify(commitmentRepository, never()).finalize(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("No PolicyVersionViolation configured for this policyVersionId -> IllegalStateException (no penalty without valid policy)")
        void resolve_NoPolicyConfiguration_FailsClosed() {
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));
            when(violationDefinitionRepository.findById(VIOLATION_A_ID)).thenReturn(Optional.of(violationA));
            when(policyVersionViolationRepository.findByPolicyVersionIdAndViolationDefinitionId(POLICY_VERSION_ID, VIOLATION_A_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> engine.resolve(COMMITMENT_ID, POLICY_VERSION_ID, List.of(VIOLATION_A_ID), RESOLVED_BY,
                    TriggerSource.SALE_FAILED_C, AUCTION_ID))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(depositService);
            verify(commitmentRepository, never()).finalize(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Commitment not AWAITING_FINALIZATION (FROZEN) -> IllegalStateException")
        void resolve_CommitmentFrozen_Rejects() {
            commitment.setStatus(CommitmentStatus.FROZEN);
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));

            assertThatThrownBy(() -> engine.resolve(COMMITMENT_ID, POLICY_VERSION_ID, List.of(VIOLATION_A_ID), RESOLVED_BY,
                    TriggerSource.SALE_FAILED_C, AUCTION_ID))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(depositService, violationDefinitionRepository);
        }
    }

    @Nested
    @DisplayName("Idempotency")
    class IdempotencyTests {

        @Test
        @DisplayName("Already FINALIZED/C with the SAME violation set -> safe no-op")
        void resolve_AlreadyFinalizedSameViolationSet_SafeNoOp() {
            commitment.setStatus(CommitmentStatus.FINALIZED);
            commitment.setFinalizationOutcome(FinalizationOutcome.C);
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));

            SellerViolationResolution existingResolution = mock(SellerViolationResolution.class);
            when(existingResolution.getId()).thenReturn(77L);
            when(sellerViolationResolutionRepository.findBySellerDiagnosticCommitmentId(COMMITMENT_ID))
                    .thenReturn(Optional.of(existingResolution));
            SellerViolationResolutionItem existingItem = mock(SellerViolationResolutionItem.class);
            when(existingItem.getViolationDefinitionId()).thenReturn(VIOLATION_A_ID);
            when(sellerViolationResolutionRepository.findItemsByResolutionId(77L)).thenReturn(List.of(existingItem));

            engine.resolve(COMMITMENT_ID, POLICY_VERSION_ID, List.of(VIOLATION_A_ID), RESOLVED_BY,
                    TriggerSource.SALE_FAILED_C, AUCTION_ID);

            verifyNoInteractions(depositService);
            verify(commitmentRepository, never()).finalize(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Already FINALIZED/C with a DIFFERENT violation set -> fails closed")
        void resolve_AlreadyFinalizedDifferentViolationSet_FailsClosed() {
            commitment.setStatus(CommitmentStatus.FINALIZED);
            commitment.setFinalizationOutcome(FinalizationOutcome.C);
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));

            SellerViolationResolution existingResolution = mock(SellerViolationResolution.class);
            when(existingResolution.getId()).thenReturn(77L);
            when(sellerViolationResolutionRepository.findBySellerDiagnosticCommitmentId(COMMITMENT_ID))
                    .thenReturn(Optional.of(existingResolution));
            SellerViolationResolutionItem existingItem = mock(SellerViolationResolutionItem.class);
            when(existingItem.getViolationDefinitionId()).thenReturn(VIOLATION_B_ID);
            when(sellerViolationResolutionRepository.findItemsByResolutionId(77L)).thenReturn(List.of(existingItem));

            assertThatThrownBy(() -> engine.resolve(COMMITMENT_ID, POLICY_VERSION_ID, List.of(VIOLATION_A_ID), RESOLVED_BY,
                    TriggerSource.SALE_FAILED_C, AUCTION_ID))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(depositService);
        }

        @Test
        @DisplayName("Already FINALIZED with a DIFFERENT outcome (e.g. B) -> fails closed")
        void resolve_AlreadyFinalizedDifferentOutcome_FailsClosed() {
            commitment.setStatus(CommitmentStatus.FINALIZED);
            commitment.setFinalizationOutcome(FinalizationOutcome.B);
            when(commitmentRepository.findById(COMMITMENT_ID)).thenReturn(Optional.of(commitment));

            assertThatThrownBy(() -> engine.resolve(COMMITMENT_ID, POLICY_VERSION_ID, List.of(VIOLATION_A_ID), RESOLVED_BY,
                    TriggerSource.SALE_FAILED_C, AUCTION_ID))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(depositService, sellerViolationResolutionRepository);
        }
    }
}
