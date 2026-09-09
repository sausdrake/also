// auction/application/SaleFailureResolutionOrchestratorTest.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.commitment.domain.FinalizationOutcome;
import com.example.autoauction.sale.application.SaleResolutionService;
import com.example.autoauction.sale.domain.FaultParty;
import com.example.autoauction.sale.domain.SaleResolution;
import com.example.autoauction.sale.domain.SaleResolutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("X3-A: SaleFailureResolutionOrchestrator")
class SaleFailureResolutionOrchestratorTest {

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private com.example.autoauction.vehicle.application.VehicleService vehicleService;

    @Mock
    private SaleResolutionService saleResolutionService;

    @Mock
    private DepositService depositService;

    @Mock
    private SaleCommitmentResolutionOrchestrator saleCommitmentResolutionOrchestrator;

    @InjectMocks
    private SaleFailureResolutionOrchestrator orchestrator;

    private Auction waitingAuction;
    private final Long vehicleId = 5L;
    private final Long winnerId = 100L;
    private final Long resolvedBy = 1L;
    private final OffsetDateTime fixedTime = OffsetDateTime.now();

    @BeforeEach
    void setUp() {
        waitingAuction = new Auction(
                vehicleId,
                new BigDecimal("1000000"),
                new BigDecimal("1200000"),
                new BigDecimal("1500000"),
                new BigDecimal("10000"),
                fixedTime.minusDays(7),
                fixedTime.minusHours(1),
                10L
        );
        waitingAuction.setId(3L);
        waitingAuction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        waitingAuction.setWinnerId(winnerId);
        waitingAuction.setWinningBid(new BigDecimal("1020000"));
    }

    private SaleResolution stubResolution(SaleResolutionResult outcome, FaultParty faultParty) {
        return new SaleResolution(waitingAuction.getId(), outcome, faultParty, null, resolvedBy, fixedTime, fixedTime);
    }

    @Nested
    @DisplayName("Guard: SUCCESS / не-WAITING_FOR_PAYMENT")
    class GuardTests {

        @Test
        @DisplayName("outcome=SUCCESS -> IllegalArgumentException до обращения к коллабораторам")
        void resolveSaleFailure_Success_RejectedBeforeTouchingCollaborators() {
            assertThatThrownBy(() -> orchestrator.resolveSaleFailure(
                    waitingAuction, SaleResolutionResult.SUCCESS, null, null, resolvedBy))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(saleResolutionService, vehicleService, depositService);
            verify(auctionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Auction не WAITING_FOR_PAYMENT -> IllegalStateException до обращения к коллабораторам")
        void resolveSaleFailure_AuctionNotWaitingForPayment_RejectedBeforeTouchingCollaborators() {
            waitingAuction.setStatus(AuctionStatus.ACTIVE);

            assertThatThrownBy(() -> orchestrator.resolveSaleFailure(
                    waitingAuction, SaleResolutionResult.B, null, null, resolvedBy))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(saleResolutionService, vehicleService, depositService);
            verify(auctionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Аукцион уже SALE_FAILED (повторное разрешение) -> отклоняется, коллабораторы не вызываются")
        void resolveSaleFailure_AlreadySaleFailed_RejectedWithoutTouchingCollaborators() {
            waitingAuction.setStatus(AuctionStatus.SALE_FAILED);

            assertThatThrownBy(() -> orchestrator.resolveSaleFailure(
                    waitingAuction, SaleResolutionResult.B, null, null, resolvedBy))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(saleResolutionService, vehicleService, depositService);
            verify(auctionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Outcome dispatch: B")
    class OutcomeBTests {

        @Test
        @DisplayName("B -> createFailure -> markSaleFailed -> returnToApproved -> unblockForSaleFailure -> save (порядок обязателен)")
        void resolveSaleFailure_OutcomeB_EnforcesOrderingAndDispatch() {
            when(saleResolutionService.createFailure(waitingAuction.getId(), SaleResolutionResult.B, null, null, resolvedBy))
                    .thenReturn(stubResolution(SaleResolutionResult.B, null));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

            orchestrator.resolveSaleFailure(waitingAuction, SaleResolutionResult.B, null, null, resolvedBy);

            assertThat(waitingAuction.getStatus()).isEqualTo(AuctionStatus.SALE_FAILED);

            InOrder inOrder = inOrder(saleResolutionService, vehicleService, depositService, auctionRepository);
            inOrder.verify(saleResolutionService).createFailure(waitingAuction.getId(), SaleResolutionResult.B, null, null, resolvedBy);
            inOrder.verify(vehicleService).returnToApproved(vehicleId);
            inOrder.verify(depositService).unblockForSaleFailure(waitingAuction.getId(), winnerId);
            inOrder.verify(auctionRepository).save(waitingAuction);
            inOrder.verify(saleResolutionService).markFinancialResolutionDone(isNull());

            verify(vehicleService, never()).markNeedsRediagnostic(any());
            verify(depositService, never()).confiscateToPlatform(any(), any());
            // X3-D: waitingAuction has no commitment (default null) - resolver must never be
            // invoked when there is nothing to resolve.
            verifyNoInteractions(saleCommitmentResolutionOrchestrator);
        }
    }

    @Nested
    @DisplayName("Outcome dispatch: BUYER_FAULT")
    class OutcomeBuyerFaultTests {

        @Test
        @DisplayName("BUYER_FAULT -> returnToApproved + confiscateToPlatform (никогда unblockForSaleFailure)")
        void resolveSaleFailure_OutcomeBuyerFault_DispatchesReturnApprovedAndConfiscate() {
            when(saleResolutionService.createFailure(waitingAuction.getId(), SaleResolutionResult.BUYER_FAULT,
                    FaultParty.BUYER, null, resolvedBy))
                    .thenReturn(stubResolution(SaleResolutionResult.BUYER_FAULT, FaultParty.BUYER));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

            orchestrator.resolveSaleFailure(waitingAuction, SaleResolutionResult.BUYER_FAULT, FaultParty.BUYER, null, resolvedBy);

            verify(vehicleService, times(1)).returnToApproved(vehicleId);
            verify(depositService, times(1)).confiscateToPlatform(waitingAuction.getId(), winnerId);
            verify(vehicleService, never()).markNeedsRediagnostic(any());
            verify(depositService, never()).unblockForSaleFailure(any(), any());
            verify(saleResolutionService, times(1)).markFinancialResolutionDone(isNull());
            verifyNoInteractions(saleCommitmentResolutionOrchestrator);
        }
    }

    @Nested
    @DisplayName("Outcome dispatch: PLATFORM_FAULT")
    class OutcomePlatformFaultTests {

        @Test
        @DisplayName("PLATFORM_FAULT -> markNeedsRediagnostic + unblockForSaleFailure (никогда confiscateToPlatform)")
        void resolveSaleFailure_OutcomePlatformFault_DispatchesRediagnosticAndUnblock() {
            when(saleResolutionService.createFailure(waitingAuction.getId(), SaleResolutionResult.PLATFORM_FAULT,
                    FaultParty.PLATFORM, null, resolvedBy))
                    .thenReturn(stubResolution(SaleResolutionResult.PLATFORM_FAULT, FaultParty.PLATFORM));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

            orchestrator.resolveSaleFailure(waitingAuction, SaleResolutionResult.PLATFORM_FAULT, FaultParty.PLATFORM, null, resolvedBy);

            verify(vehicleService, times(1)).markNeedsRediagnostic(vehicleId);
            verify(depositService, times(1)).unblockForSaleFailure(waitingAuction.getId(), winnerId);
            verify(vehicleService, never()).returnToApproved(any());
            verify(depositService, never()).confiscateToPlatform(any(), any());
            verify(saleResolutionService, times(1)).markFinancialResolutionDone(isNull());
            verifyNoInteractions(saleCommitmentResolutionOrchestrator);
        }
    }

    @Nested
    @DisplayName("Outcome dispatch: C")
    class OutcomeCTests {

        @Test
        @DisplayName("C -> markNeedsRediagnostic + unblockForSaleFailure (никогда confiscateToPlatform)")
        void resolveSaleFailure_OutcomeC_DispatchesRediagnosticAndUnblock() {
            when(saleResolutionService.createFailure(waitingAuction.getId(), SaleResolutionResult.C,
                    FaultParty.SELLER, null, resolvedBy))
                    .thenReturn(stubResolution(SaleResolutionResult.C, FaultParty.SELLER));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

            orchestrator.resolveSaleFailure(waitingAuction, SaleResolutionResult.C, FaultParty.SELLER, null, resolvedBy);

            verify(vehicleService, times(1)).markNeedsRediagnostic(vehicleId);
            verify(depositService, times(1)).unblockForSaleFailure(waitingAuction.getId(), winnerId);
            verify(vehicleService, never()).returnToApproved(any());
            verify(depositService, never()).confiscateToPlatform(any(), any());
        }

        @Test
        @DisplayName("X3-D: C никогда не финализирует commitment и никогда не переводит financialResolutionStatus в DONE (даже если commitment существует)")
        void resolveSaleFailure_OutcomeC_NeverTouchesCommitmentOrFinancialStatus() {
            waitingAuction.setSellerDiagnosticCommitmentId(777L);
            when(saleResolutionService.createFailure(waitingAuction.getId(), SaleResolutionResult.C,
                    FaultParty.SELLER, null, resolvedBy))
                    .thenReturn(stubResolution(SaleResolutionResult.C, FaultParty.SELLER));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

            orchestrator.resolveSaleFailure(waitingAuction, SaleResolutionResult.C, FaultParty.SELLER, null, resolvedBy);

            verifyNoInteractions(saleCommitmentResolutionOrchestrator);
            verify(saleResolutionService, never()).markFinancialResolutionDone(any());
        }
    }

    @Nested
    @DisplayName("X3-D: seller commitment consequence")
    class CommitmentConsequenceTests {

        @Test
        @DisplayName("B + commitment существует -> resolve(commitmentId, B) + markFinancialResolutionDone(id)")
        void resolveSaleFailure_OutcomeB_WithCommitment_ResolvesAsB() {
            waitingAuction.setSellerDiagnosticCommitmentId(777L);
            SaleResolution resolution = new SaleResolution(9L, waitingAuction.getId(), SaleResolutionResult.B,
                    null, null, resolvedBy, fixedTime, null, null,
                    com.example.autoauction.sale.domain.FinancialResolutionStatus.PENDING, fixedTime);
            when(saleResolutionService.createFailure(waitingAuction.getId(), SaleResolutionResult.B, null, null, resolvedBy))
                    .thenReturn(resolution);
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

            orchestrator.resolveSaleFailure(waitingAuction, SaleResolutionResult.B, null, null, resolvedBy);

            InOrder inOrder = inOrder(saleCommitmentResolutionOrchestrator, auctionRepository, saleResolutionService);
            inOrder.verify(saleCommitmentResolutionOrchestrator).resolve(777L, FinalizationOutcome.B);
            inOrder.verify(auctionRepository).save(waitingAuction);
            inOrder.verify(saleResolutionService).markFinancialResolutionDone(9L);
        }

        @Test
        @DisplayName("BUYER_FAULT + commitment существует -> resolve(commitmentId, A) - НЕ отдельное значение BUYER_FAULT")
        void resolveSaleFailure_OutcomeBuyerFault_WithCommitment_ResolvesAsA() {
            waitingAuction.setSellerDiagnosticCommitmentId(777L);
            SaleResolution resolution = new SaleResolution(9L, waitingAuction.getId(), SaleResolutionResult.BUYER_FAULT,
                    null, FaultParty.BUYER, resolvedBy, fixedTime, null, null,
                    com.example.autoauction.sale.domain.FinancialResolutionStatus.PENDING, fixedTime);
            when(saleResolutionService.createFailure(waitingAuction.getId(), SaleResolutionResult.BUYER_FAULT,
                    FaultParty.BUYER, null, resolvedBy)).thenReturn(resolution);
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

            orchestrator.resolveSaleFailure(waitingAuction, SaleResolutionResult.BUYER_FAULT, FaultParty.BUYER, null, resolvedBy);

            verify(saleCommitmentResolutionOrchestrator).resolve(777L, FinalizationOutcome.A);
            verify(saleResolutionService).markFinancialResolutionDone(9L);
        }

        @Test
        @DisplayName("PLATFORM_FAULT + commitment существует -> resolve(commitmentId, A)")
        void resolveSaleFailure_OutcomePlatformFault_WithCommitment_ResolvesAsA() {
            waitingAuction.setSellerDiagnosticCommitmentId(777L);
            SaleResolution resolution = new SaleResolution(9L, waitingAuction.getId(), SaleResolutionResult.PLATFORM_FAULT,
                    null, FaultParty.PLATFORM, resolvedBy, fixedTime, null, null,
                    com.example.autoauction.sale.domain.FinancialResolutionStatus.PENDING, fixedTime);
            when(saleResolutionService.createFailure(waitingAuction.getId(), SaleResolutionResult.PLATFORM_FAULT,
                    FaultParty.PLATFORM, null, resolvedBy)).thenReturn(resolution);
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

            orchestrator.resolveSaleFailure(waitingAuction, SaleResolutionResult.PLATFORM_FAULT, FaultParty.PLATFORM, null, resolvedBy);

            verify(saleCommitmentResolutionOrchestrator).resolve(777L, FinalizationOutcome.A);
            verify(saleResolutionService).markFinancialResolutionDone(9L);
        }
    }

    @Nested
    @DisplayName("Propagation: failure at each step")
    class FailurePropagationTests {

        @Test
        @DisplayName("createFailure бросает -> распространяется, Vehicle/Deposit/save не вызываются")
        void resolveSaleFailure_CreateFailureThrows_PropagatesWithoutTouchingVehicleDepositOrSave() {
            when(saleResolutionService.createFailure(any(), any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("SaleResolution уже существует для аукциона 3"));

            assertThatThrownBy(() -> orchestrator.resolveSaleFailure(
                    waitingAuction, SaleResolutionResult.B, null, null, resolvedBy))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("уже существует");

            assertThat(waitingAuction.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
            verifyNoInteractions(vehicleService, depositService, saleCommitmentResolutionOrchestrator);
            verify(auctionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Vehicle-консеквенция бросает -> распространяется, Deposit/save не вызываются")
        void resolveSaleFailure_VehicleConsequenceThrows_PropagatesWithoutTouchingDepositOrSave() {
            when(saleResolutionService.createFailure(any(), any(), any(), any(), any()))
                    .thenReturn(stubResolution(SaleResolutionResult.B, null));
            doThrow(new IllegalStateException("Only IN_AUCTION can be returned to APPROVED"))
                    .when(vehicleService).returnToApproved(vehicleId);

            assertThatThrownBy(() -> orchestrator.resolveSaleFailure(
                    waitingAuction, SaleResolutionResult.B, null, null, resolvedBy))
                    .isInstanceOf(IllegalStateException.class);

            // auction.markSaleFailed() already ran in-memory before the failure (acceptable -
            // real transactional rollback is proven separately by the integration test).
            assertThat(waitingAuction.getStatus()).isEqualTo(AuctionStatus.SALE_FAILED);
            verifyNoInteractions(depositService, saleCommitmentResolutionOrchestrator);
            verify(auctionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Deposit-консеквенция бросает -> распространяется, save не вызывается")
        void resolveSaleFailure_DepositConsequenceThrows_PropagatesWithoutSaving() {
            when(saleResolutionService.createFailure(any(), any(), any(), any(), any()))
                    .thenReturn(stubResolution(SaleResolutionResult.B, null));
            doThrow(new IllegalStateException("Заморозка победителя не найдена"))
                    .when(depositService).unblockForSaleFailure(any(), any());

            assertThatThrownBy(() -> orchestrator.resolveSaleFailure(
                    waitingAuction, SaleResolutionResult.B, null, null, resolvedBy))
                    .isInstanceOf(IllegalStateException.class);

            verify(vehicleService, times(1)).returnToApproved(vehicleId);
            verify(auctionRepository, never()).save(any());
            verifyNoInteractions(saleCommitmentResolutionOrchestrator);
        }

        @Test
        @DisplayName("X3-D: commitment-консеквенция бросает -> распространяется, save/markFinancialResolutionDone не вызываются")
        void resolveSaleFailure_CommitmentConsequenceThrows_PropagatesWithoutSavingOrMarkingDone() {
            waitingAuction.setSellerDiagnosticCommitmentId(777L);
            when(saleResolutionService.createFailure(any(), any(), any(), any(), any()))
                    .thenReturn(stubResolution(SaleResolutionResult.B, null));
            doThrow(new IllegalStateException("Commitment 777 не готов к финализации"))
                    .when(saleCommitmentResolutionOrchestrator).resolve(777L, FinalizationOutcome.B);

            assertThatThrownBy(() -> orchestrator.resolveSaleFailure(
                    waitingAuction, SaleResolutionResult.B, null, null, resolvedBy))
                    .isInstanceOf(IllegalStateException.class);

            verify(vehicleService, times(1)).returnToApproved(vehicleId);
            verify(depositService, times(1)).unblockForSaleFailure(waitingAuction.getId(), winnerId);
            verify(auctionRepository, never()).save(any());
            verify(saleResolutionService, never()).markFinancialResolutionDone(any());
        }
    }
}
