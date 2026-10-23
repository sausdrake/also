package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.PaymentDeadlineChangeType;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.domain.port.PaymentDeadlineHistoryRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.commitment.domain.FinalizationOutcome;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.policy.domain.PolicyVersion;
import com.example.autoauction.sale.application.SaleResolutionService;
import com.example.autoauction.sale.domain.FinancialResolutionStatus;
import com.example.autoauction.sale.domain.SaleResolution;
import com.example.autoauction.sale.domain.SaleResolutionResult;
import com.example.autoauction.settlement.application.SettlementService;
import com.example.autoauction.vehicle.application.VehicleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// auction/application/AuctionFinalizerTest.java

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты AuctionFinalizer")
class AuctionFinalizerTest {

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private DepositService depositService;

    @Mock
    private AuctionParticipantRepository participantRepository;

    @Mock
    private Clock clock;

    @Mock
    private PolicyVersionService policyVersionService;

    @Mock
    private VehicleService vehicleService;

    @Mock
    private SaleResolutionService saleResolutionService;

    @Mock
    private SaleCommitmentResolutionOrchestrator saleCommitmentResolutionOrchestrator;

    @Mock
    private SettlementService settlementService;

    @Mock
    private StandardPaymentDeadlineCalculator standardPaymentDeadlineCalculator;

    @Mock
    private PaymentDeadlineHistoryRepository paymentDeadlineHistoryRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private AuctionNoSaleResolutionOrchestrator noSaleResolutionOrchestrator;

    @InjectMocks
    private AuctionFinalizer auctionFinalizer;

    private OffsetDateTime fixedTime;
    private Auction activeAuction;
    private final Long auctionId = 1L;
    private final Long sellerId = 10L;
    private final Long winnerId = 100L;
    private final Long loserId = 101L;
    private final BigDecimal startingPrice = new BigDecimal("1000000");

    @BeforeEach
    void setUp() {
        Instant fixedInstant = Instant.parse("2024-01-01T00:00:00Z");
        fixedTime = OffsetDateTime.ofInstant(fixedInstant, ZoneId.of("UTC"));
        when(clock.instant()).thenReturn(fixedInstant);
        when(clock.getZone()).thenReturn(ZoneId.of("UTC"));

        activeAuction = new Auction(
                1L,
                startingPrice,
                new BigDecimal("1200000"),
                new BigDecimal("1500000"),
                new BigDecimal("10000"),
                fixedTime.minusHours(2),
                fixedTime.plusHours(2),
                sellerId,
                clock
        );
        activeAuction.setId(auctionId);
        activeAuction.setStatus(AuctionStatus.ACTIVE);
        activeAuction.setCurrentPrice(startingPrice);
        activeAuction.setTotalBids(0);
    }

    @Nested
    @DisplayName("Финализация аукциона без ставок")
    class NoBidsTests {

        @Test
        @DisplayName("Аукцион без ставок -> EXPIRED, депозит размораживается")
        void finalizeAuction_NoBids_ShouldExpireAndUnfreezeDeposit() {
            // given
            when(participantRepository.getParticipants(auctionId)).thenReturn(List.of(winnerId, loserId));
            // Исправлено: правильные параметры для мока
            doNothing().when(depositService).unfreezeForAuction(eq(winnerId), eq(auctionId));
            doNothing().when(depositService).unfreezeForAuction(eq(loserId), eq(auctionId));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));
            doNothing().when(participantRepository).clearParticipants(auctionId);

            // when
            boolean result = auctionFinalizer.finalizeAuction(activeAuction);

            // then
            assertThat(result).isTrue();
            assertThat(activeAuction.getStatus()).isEqualTo(AuctionStatus.EXPIRED);
            assertThat(activeAuction.getWinnerId()).isNull();
            assertThat(activeAuction.getWinningBid()).isNull();
            assertThat(activeAuction.getPaymentDeadline()).isNull();
            verifyNoInteractions(standardPaymentDeadlineCalculator, paymentDeadlineHistoryRepository);

            verify(depositService, times(2)).unfreezeForAuction(anyLong(), eq(auctionId));
            verify(participantRepository, times(1)).clearParticipants(auctionId);
            verify(auctionRepository, times(1)).save(activeAuction);
            // AUC-NOSALE-01: EXPIRED returns the seller's guarantee and frees the vehicle - once
            verify(noSaleResolutionOrchestrator, times(1)).resolve(activeAuction, List.of(), null);

            // MVP-003: side effects are deferred via an AFTER_COMMIT event, never called
            // directly. No winner - winnerId/winningBid must be null, and participantIds must
            // still carry the original participant list even though clearParticipants() already ran.
            ArgumentCaptor<AuctionFinalizedEvent> captor = ArgumentCaptor.forClass(AuctionFinalizedEvent.class);
            verify(eventPublisher, times(1)).publishEvent(captor.capture());
            AuctionFinalizedEvent event = captor.getValue();
            assertThat(event.auction()).isSameAs(activeAuction);
            assertThat(event.winnerId()).isNull();
            assertThat(event.winningBid()).isNull();
            assertThat(event.participantIds()).containsExactly(winnerId, loserId);
        }
    }

    @Nested
    @DisplayName("MVP-003: finalizeAuction на уже завершённом аукционе")
    class FinalizeAuctionAlreadyFinishedGuardTests {

        @Test
        @DisplayName("Аукцион уже завершён -> метод не делает ничего, событие не публикуется")
        void finalizeAuction_OnAlreadyFinishedAuction_PublishesNoEvent() {
            activeAuction.setStatus(AuctionStatus.COMPLETED);

            boolean result = auctionFinalizer.finalizeAuction(activeAuction);

            assertThat(result).isFalse();
            assertThat(activeAuction.getStatus()).isEqualTo(AuctionStatus.COMPLETED);
            verify(auctionRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    @DisplayName("Отмена аукциона (D-1)")
    class CancelAuctionTests {

        @Test
        @DisplayName("Отмена активного аукциона со ставками -> CANCELLED, все участники разморожены")
        void cancelAuction_ActiveWithBids_ShouldCancelAndUnfreezeAllParticipants() {
            // given
            activeAuction.setTotalBids(5);
            when(participantRepository.getParticipants(auctionId)).thenReturn(List.of(winnerId, loserId));
            doNothing().when(depositService).unfreezeForAuction(eq(winnerId), eq(auctionId));
            doNothing().when(depositService).unfreezeForAuction(eq(loserId), eq(auctionId));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));
            doNothing().when(participantRepository).clearParticipants(auctionId);

            // when
            boolean result = auctionFinalizer.cancelAuction(activeAuction);

            // then
            assertThat(result).isTrue();
            assertThat(activeAuction.getStatus()).isEqualTo(AuctionStatus.CANCELLED);
            // Критично: даже "победитель" по текущей цене разблокируется - у отменённого
            // аукциона нет settled-победителя.
            verify(depositService, times(1)).unfreezeForAuction(winnerId, auctionId);
            verify(depositService, times(1)).unfreezeForAuction(loserId, auctionId);
            verify(participantRepository, times(1)).clearParticipants(auctionId);
            verify(auctionRepository, times(1)).save(activeAuction);
            // AUC-NOSALE-01: guarantee + vehicle consequences exactly once, no violations
            verify(noSaleResolutionOrchestrator, times(1)).resolve(activeAuction, List.of(), null);

            // MVP-004: side effects are deferred via an AFTER_COMMIT event, never called
            // directly. participantIds must still carry the original participant list even
            // though clearParticipants() already ran.
            ArgumentCaptor<AuctionCancelledEvent> captor = ArgumentCaptor.forClass(AuctionCancelledEvent.class);
            verify(eventPublisher, times(1)).publishEvent(captor.capture());
            AuctionCancelledEvent event = captor.getValue();
            assertThat(event.auction()).isSameAs(activeAuction);
            assertThat(event.participantIds()).containsExactly(winnerId, loserId);
        }

        @Test
        @DisplayName("Отмена аукциона без участников -> CANCELLED, unfreezeForAuction не вызывается")
        void cancelAuction_NoParticipants_ShouldCancelWithoutUnfreezeCalls() {
            // given
            when(participantRepository.getParticipants(auctionId)).thenReturn(List.of());
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));
            doNothing().when(participantRepository).clearParticipants(auctionId);

            // when
            boolean result = auctionFinalizer.cancelAuction(activeAuction);

            // then
            assertThat(result).isTrue();
            assertThat(activeAuction.getStatus()).isEqualTo(AuctionStatus.CANCELLED);
            verify(depositService, never()).unfreezeForAuction(anyLong(), anyLong());
        }

        @Test
        @DisplayName("Отмена уже завершённого аукциона -> идемпотентно, ничего не делает")
        void cancelAuction_AlreadyFinished_ShouldReturnFalseAndDoNothing() {
            // given
            activeAuction.setStatus(AuctionStatus.COMPLETED);

            // when
            boolean result = auctionFinalizer.cancelAuction(activeAuction);

            // then
            assertThat(result).isFalse();
            verify(auctionRepository, never()).save(any());
            verify(participantRepository, never()).clearParticipants(anyLong());
            verifyNoInteractions(depositService);
            // MVP-004: the early-return guard must publish zero side-effect events.
            verifyNoInteractions(eventPublisher);
            // AUC-NOSALE-01: a repeated cancellation never releases the guarantee a second time
            verifyNoInteractions(noSaleResolutionOrchestrator);
        }
    }

    @Nested
    @DisplayName("Vehicle Auction-State Wiring: запуск аукциона (startAuction)")
    class StartAuctionTests {

        private Auction createdAuction;
        private final Long vehicleId = 5L;

        @BeforeEach
        void setUpCreatedAuction() {
            createdAuction = new Auction(
                    vehicleId,
                    startingPrice,
                    new BigDecimal("1200000"),
                    new BigDecimal("1500000"),
                    new BigDecimal("10000"),
                    fixedTime.plusHours(1),
                    fixedTime.plusDays(7),
                    sellerId,
                    clock
            );
            createdAuction.setId(2L);
        }

        @Test
        @DisplayName("CREATED auction -> Auction.start() выполняется ДО VehicleService.markInAuction(), которое выполняется ДО save (порядок обязателен)")
        void startAuction_ValidCreatedAuction_EnforcesMandatoryOrdering() {
            // given
            java.util.concurrent.atomic.AtomicReference<AuctionStatus> statusAtVehicleCallTime =
                    new java.util.concurrent.atomic.AtomicReference<>();
            doAnswer(inv -> {
                // Captures Auction's status at the exact moment VehicleService is invoked -
                // direct proof that Auction.start() already ran, not an inference.
                statusAtVehicleCallTime.set(createdAuction.getStatus());
                return null;
            }).when(vehicleService).markInAuction(vehicleId);
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            auctionFinalizer.startAuction(createdAuction);

            // then
            assertThat(statusAtVehicleCallTime.get())
                    .as("Auction.start() must have already run before VehicleService.markInAuction() is called")
                    .isEqualTo(AuctionStatus.ACTIVE);
            assertThat(createdAuction.getStatus()).isEqualTo(AuctionStatus.ACTIVE);

            InOrder inOrder = inOrder(vehicleService, auctionRepository);
            inOrder.verify(vehicleService).markInAuction(vehicleId);
            inOrder.verify(auctionRepository).save(createdAuction);
        }

        @Test
        @DisplayName("Уже ACTIVE auction -> Auction.start() бросает ДО обращения к VehicleService (non-idempotent, guard сохранён)")
        void startAuction_AlreadyActiveAuction_ThrowsBeforeTouchingVehicle() {
            // given
            createdAuction.setStatus(AuctionStatus.ACTIVE);

            // when & then
            assertThatThrownBy(() -> auctionFinalizer.startAuction(createdAuction))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Невозможно запустить аукцион");

            // Vehicle.markInAuction() must never be reached for a second start attempt - this
            // is exactly what makes idempotency on the Vehicle side unnecessary (locked
            // decisions 2 and 3): the Auction guard alone prevents a repeat call.
            verifyNoInteractions(vehicleService);
            verify(auctionRepository, never()).save(any());
        }

        @Test
        @DisplayName("VehicleService.markInAuction() выбрасывает исключение -> оно распространяется наружу, Auction не сохраняется")
        void startAuction_VehicleTransitionFails_PropagatesExceptionAndNeverSavesAuction() {
            // given
            doThrow(new IllegalStateException("Only APPROVED can be moved to auction"))
                    .when(vehicleService).markInAuction(vehicleId);

            // when & then
            assertThatThrownBy(() -> auctionFinalizer.startAuction(createdAuction))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only APPROVED can be moved to auction");

            // Auction.start() already ran in-memory before the failure, but the exception is
            // not swallowed anywhere (not into a FailedOperation, not logged-and-continued) -
            // it propagates straight out, and auctionRepository.save() (which runs after the
            // Vehicle call) is never reached. Real transactional rollback of the in-memory
            // Auction.start() effect is proven separately by a dedicated integration test
            // (this Mockito test cannot exercise a real transaction), but this proves the
            // failure is never masked and the Auction side is never persisted when Vehicle
            // fails.
            verify(auctionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("X2 (Authoritative Sale Completion): завершение продажи (completeSale)")
    class CompleteSaleTests {

        private Auction waitingAuction;
        private final Long vehicleId = 5L;
        private final Long resolvedBy = 1L;
        private final BigDecimal winningBid = new BigDecimal("1020000");

        @BeforeEach
        void setUpWaitingAuction() {
            waitingAuction = new Auction(
                    vehicleId,
                    startingPrice,
                    new BigDecimal("1200000"),
                    new BigDecimal("1500000"),
                    new BigDecimal("10000"),
                    fixedTime.minusDays(7),
                    fixedTime.minusHours(1),
                    sellerId,
                    clock
            );
            waitingAuction.setId(3L);
            waitingAuction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
            waitingAuction.setWinnerId(winnerId);
            waitingAuction.setWinningBid(winningBid);
        }

        private SaleResolution stubSaleResolution() {
            // Uses a fixed literal timestamp rather than OffsetDateTime.now(clock) - clock is
            // itself a Mockito mock, and invoking it while this helper's result is still being
            // computed as an argument to an in-progress when(...).thenReturn(...) stub (see call
            // sites below) would interleave mock interactions mid-stub and trip Mockito's
            // UnfinishedStubbingException.
            return new SaleResolution(99L, waitingAuction.getId(), SaleResolutionResult.SUCCESS, null, null,
                    resolvedBy, fixedTime, 10L, 20L, FinancialResolutionStatus.PENDING, fixedTime);
        }

        @Test
        @DisplayName("WAITING_FOR_PAYMENT -> createSuccess() выполняется ДО Auction.markSold(), которое выполняется ДО VehicleService.markAsSold(), которое выполняется ДО save (порядок обязателен)")
        void completeSale_ValidWaitingAuction_EnforcesMandatoryOrdering() {
            // given
            java.util.concurrent.atomic.AtomicReference<AuctionStatus> statusAtVehicleCallTime =
                    new java.util.concurrent.atomic.AtomicReference<>();
            when(saleResolutionService.createSuccess(waitingAuction.getId(), resolvedBy))
                    .thenReturn(stubSaleResolution());
            doAnswer(inv -> {
                // Captures Auction's status at the exact moment VehicleService is invoked -
                // direct proof that auction.markSold() already ran, not an inference.
                statusAtVehicleCallTime.set(waitingAuction.getStatus());
                return null;
            }).when(vehicleService).markAsSold(vehicleId);
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            auctionFinalizer.completeSale(waitingAuction, resolvedBy);

            // then
            assertThat(statusAtVehicleCallTime.get())
                    .as("auction.markSold() must have already run before VehicleService.markAsSold() is called")
                    .isEqualTo(AuctionStatus.SOLD);
            assertThat(waitingAuction.getStatus()).isEqualTo(AuctionStatus.SOLD);

            InOrder inOrder = inOrder(saleResolutionService, vehicleService, depositService, auctionRepository,
                    settlementService);
            inOrder.verify(saleResolutionService).createSuccess(waitingAuction.getId(), resolvedBy);
            inOrder.verify(vehicleService).markAsSold(vehicleId);
            inOrder.verify(depositService).releaseWinnerDeposit(winnerId, waitingAuction.getId());
            inOrder.verify(auctionRepository).save(waitingAuction);
            inOrder.verify(saleResolutionService).markFinancialResolutionDone(99L);
            // Step 2C: Settlement creation is the last step, inside this same transaction.
            inOrder.verify(settlementService).createPending(waitingAuction);

            // waitingAuction has no commitment (default null) - resolver must never be invoked.
            verifyNoInteractions(saleCommitmentResolutionOrchestrator);
        }

        @Test
        @DisplayName("X3-D: commitment существует -> saleCommitmentResolutionOrchestrator.resolve(id, A) вызывается перед save")
        void completeSale_WithCommitment_ResolvesOutcomeABeforeSave() {
            waitingAuction.setSellerDiagnosticCommitmentId(555L);
            when(saleResolutionService.createSuccess(waitingAuction.getId(), resolvedBy))
                    .thenReturn(stubSaleResolution());
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

            auctionFinalizer.completeSale(waitingAuction, resolvedBy);

            InOrder inOrder = inOrder(depositService, saleCommitmentResolutionOrchestrator, auctionRepository,
                    saleResolutionService, settlementService);
            inOrder.verify(depositService).releaseWinnerDeposit(winnerId, waitingAuction.getId());
            inOrder.verify(saleCommitmentResolutionOrchestrator).resolve(555L, FinalizationOutcome.A);
            inOrder.verify(auctionRepository).save(waitingAuction);
            inOrder.verify(saleResolutionService).markFinancialResolutionDone(99L);
            inOrder.verify(settlementService).createPending(waitingAuction);
        }

        @Test
        @DisplayName("X3-D: releaseWinnerDeposit бросает -> распространяется, commitment/save/markFinancialResolutionDone не вызываются")
        void completeSale_ReleaseWinnerDepositThrows_PropagatesWithoutCommitmentOrSave() {
            when(saleResolutionService.createSuccess(waitingAuction.getId(), resolvedBy))
                    .thenReturn(stubSaleResolution());
            doThrow(new IllegalStateException("Заморозка победителя не найдена"))
                    .when(depositService).releaseWinnerDeposit(winnerId, waitingAuction.getId());

            assertThatThrownBy(() -> auctionFinalizer.completeSale(waitingAuction, resolvedBy))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(saleCommitmentResolutionOrchestrator);
            verify(auctionRepository, never()).save(any());
            verify(saleResolutionService, never()).markFinancialResolutionDone(any());
            verifyNoInteractions(settlementService);
        }

        @Test
        @DisplayName("X3-D: commitment-консеквенция бросает -> распространяется, save/markFinancialResolutionDone не вызываются")
        void completeSale_CommitmentConsequenceThrows_PropagatesWithoutSaveOrMarkingDone() {
            waitingAuction.setSellerDiagnosticCommitmentId(555L);
            when(saleResolutionService.createSuccess(waitingAuction.getId(), resolvedBy))
                    .thenReturn(stubSaleResolution());
            doThrow(new IllegalStateException("Commitment 555 не готов к финализации"))
                    .when(saleCommitmentResolutionOrchestrator).resolve(555L, FinalizationOutcome.A);

            assertThatThrownBy(() -> auctionFinalizer.completeSale(waitingAuction, resolvedBy))
                    .isInstanceOf(IllegalStateException.class);

            verify(depositService, times(1)).releaseWinnerDeposit(winnerId, waitingAuction.getId());
            verify(auctionRepository, never()).save(any());
            verify(saleResolutionService, never()).markFinancialResolutionDone(any());
            verifyNoInteractions(settlementService);
        }

        @Test
        @DisplayName("Auction не WAITING_FOR_PAYMENT -> исключение ДО обращения к SaleResolutionService/VehicleService, save не вызывается")
        void completeSale_AuctionNotWaitingForPayment_ThrowsBeforeTouchingCollaborators() {
            // given
            waitingAuction.setStatus(AuctionStatus.ACTIVE);

            // when & then
            assertThatThrownBy(() -> auctionFinalizer.completeSale(waitingAuction, resolvedBy))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Невозможно завершить продажу");

            verifyNoInteractions(saleResolutionService);
            verifyNoInteractions(vehicleService);
            verify(auctionRepository, never()).save(any());
            verifyNoInteractions(settlementService);
        }

        @Test
        @DisplayName("Аукцион уже SOLD (повторное завершение) -> отклоняется, коллабораторы не вызываются")
        void completeSale_AlreadySold_RejectedWithoutTouchingCollaborators() {
            // given
            waitingAuction.setStatus(AuctionStatus.SOLD);

            // when & then
            assertThatThrownBy(() -> auctionFinalizer.completeSale(waitingAuction, resolvedBy))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Невозможно завершить продажу");

            verifyNoInteractions(saleResolutionService);
            verifyNoInteractions(vehicleService);
            verify(auctionRepository, never()).save(any());
            verifyNoInteractions(settlementService);
        }

        @Test
        @DisplayName("SaleResolutionService.createSuccess() выбрасывает исключение (отсутствующая/некорректная evidence) -> распространяется наружу, VehicleService не вызывается, Auction не сохраняется")
        void completeSale_SaleResolutionCreationFails_PropagatesAndNeverTouchesVehicleOrSaves() {
            // given
            when(saleResolutionService.createSuccess(waitingAuction.getId(), resolvedBy))
                    .thenThrow(new IllegalStateException("PaymentEvidence отсутствует для аукциона " + waitingAuction.getId()));

            // when & then
            assertThatThrownBy(() -> auctionFinalizer.completeSale(waitingAuction, resolvedBy))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PaymentEvidence отсутствует");

            // Auction.markSold() must never run for this to hold - proven indirectly by
            // asserting the in-memory status is unchanged, since createSuccess() throws before
            // auction.markSold() is ever reached in completeSale()'s statement order.
            assertThat(waitingAuction.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
            verifyNoInteractions(vehicleService, saleCommitmentResolutionOrchestrator);
            verify(auctionRepository, never()).save(any());
            verifyNoInteractions(settlementService);
        }

        @Test
        @DisplayName("VehicleService.markAsSold() выбрасывает исключение -> оно распространяется наружу, Auction не сохраняется")
        void completeSale_VehicleTransitionFails_PropagatesExceptionAndNeverSavesAuction() {
            // given
            when(saleResolutionService.createSuccess(waitingAuction.getId(), resolvedBy))
                    .thenReturn(stubSaleResolution());
            doThrow(new IllegalStateException("Only IN_AUCTION can be sold"))
                    .when(vehicleService).markAsSold(vehicleId);

            // when & then
            assertThatThrownBy(() -> auctionFinalizer.completeSale(waitingAuction, resolvedBy))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Only IN_AUCTION can be sold");

            // auction.markSold() already ran in-memory before the failure (this is acceptable -
            // see the class-level contract), but the exception is not swallowed anywhere and
            // auctionRepository.save() (which runs after the Vehicle call) is never reached.
            // Real transactional rollback of the in-memory markSold() effect plus the
            // SaleResolution insert is proven separately by a dedicated integration test (this
            // Mockito test cannot exercise a real transaction).
            assertThat(waitingAuction.getStatus()).isEqualTo(AuctionStatus.SOLD);
            verify(auctionRepository, never()).save(any());
            verifyNoInteractions(depositService, saleCommitmentResolutionOrchestrator);
            verifyNoInteractions(settlementService);
        }
    }

    @Nested
    @DisplayName("Buy Now тесты")
    class BuyNowTests {

        @Test
        @DisplayName("Buy Now -> аукцион завершается, победитель отмечается")
        void finalizeWithBuyNow_ShouldCompleteAndMarkWinner() {
            // given
            BigDecimal buyNowAmount = new BigDecimal("1500000");
            when(participantRepository.getParticipants(auctionId)).thenReturn(List.of(winnerId, loserId));
            // Исправлено: правильные параметры для мока
            doNothing().when(depositService).unfreezeForAuction(eq(loserId), eq(auctionId));
            doNothing().when(depositService).markAsWinner(eq(winnerId), eq(auctionId));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));
            doNothing().when(participantRepository).clearParticipants(auctionId);
            OffsetDateTime deadline = fixedTime.plusDays(1);
            when(standardPaymentDeadlineCalculator.computeInitialDeadline()).thenReturn(deadline);

            // when
            auctionFinalizer.finalizeWithBuyNow(activeAuction, winnerId, buyNowAmount);

            // then
            assertThat(activeAuction.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
            assertThat(activeAuction.getWinnerId()).isEqualTo(winnerId);
            assertThat(activeAuction.getCurrentPrice()).isEqualTo(buyNowAmount);
            assertThat(activeAuction.getWinningBid()).isEqualTo(buyNowAmount);
            assertThat(activeAuction.getPaymentDeadline()).isEqualTo(deadline);

            verify(depositService, times(1)).markAsWinner(winnerId, auctionId);
            verify(depositService, times(1)).unfreezeForAuction(eq(loserId), eq(auctionId));
            verify(participantRepository, times(1)).clearParticipants(auctionId);
            verify(paymentDeadlineHistoryRepository, times(1)).save(argThat(h ->
                    h.getChangeType() == PaymentDeadlineChangeType.INITIAL
                            && h.getAuctionId().equals(auctionId)
                            && h.getNewDeadline().equals(deadline)
                            && h.getChangedByAdminId() == null
                            && h.getPreviousDeadline() == null));
            // MVP-002: WS/notification side effects are deferred via an AFTER_COMMIT event,
            // never called directly from finalizeWithBuyNow anymore.
            verify(eventPublisher, times(1)).publishEvent(any(BuyNowFinalizedEvent.class));
        }

        @Test
        @DisplayName("Buy Now на уже завершённом аукционе -> метод не делает ничего (не выбрасывает исключение)")
        void finalizeWithBuyNow_OnFinishedAuction_ShouldDoNothing() {
            // given
            activeAuction.setStatus(AuctionStatus.COMPLETED);
            BigDecimal buyNowAmount = new BigDecimal("1500000");

            // when - метод теперь не выбрасывает исключение, а просто возвращается
            auctionFinalizer.finalizeWithBuyNow(activeAuction, winnerId, buyNowAmount);

            // then - статус не изменился
            assertThat(activeAuction.getStatus()).isEqualTo(AuctionStatus.COMPLETED);
            verify(auctionRepository, never()).save(any());
            // MVP-002: the early-return guard must publish zero side-effect events, same as it
            // performs zero other side effects today.
            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    @DisplayName("Slice 1b-2: buyer-facing резервная цена при финализации")
    class PolicyVersionAwareReserveTests {

        @Test
        @DisplayName("Legacy аукцион (policyVersionId == null) -> сравнение по сырой resertPrice, PolicyVersionService не трогается")
        void finalizeAuction_LegacyAuction_ComparesRawReservePrice_NeverTouchesPolicyVersionService() {
            // given: reservePrice=1,200,000 raw, currentPrice meets it exactly
            assertThat(activeAuction.getPolicyVersionId()).isNull();
            activeAuction.setTotalBids(3);
            activeAuction.setCurrentPrice(new BigDecimal("1200000"));
            activeAuction.setWinnerId(winnerId);
            activeAuction.setWinningBid(new BigDecimal("1200000"));
            when(participantRepository.getParticipants(auctionId)).thenReturn(List.of(winnerId));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));
            OffsetDateTime deadline = fixedTime.plusDays(1);
            when(standardPaymentDeadlineCalculator.computeInitialDeadline()).thenReturn(deadline);

            // when
            boolean result = auctionFinalizer.finalizeAuction(activeAuction);

            // then
            assertThat(result).isTrue();
            assertThat(activeAuction.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
            assertThat(activeAuction.getPaymentDeadline()).isEqualTo(deadline);
            verifyNoInteractions(policyVersionService);
            verify(paymentDeadlineHistoryRepository, times(1)).save(argThat(h ->
                    h.getChangeType() == PaymentDeadlineChangeType.INITIAL
                            && h.getAuctionId().equals(auctionId)
                            && h.getNewDeadline().equals(deadline)
                            && h.getPreviousDeadline() == null
                            && h.getChangedByAdminId() == null
                            && h.getReason() != null && !h.getReason().isBlank()));
            // Statement-order proxy for "committed atomically" - both happen inside this same
            // @Transactional method; genuine rollback-on-failure atomicity is out of reach for a
            // Mockito unit test (same established limitation already documented elsewhere in this
            // class) and is covered by this codebase's real-transaction integration tests.
            InOrder inOrder = inOrder(auctionRepository, paymentDeadlineHistoryRepository);
            inOrder.verify(auctionRepository).save(activeAuction);
            inOrder.verify(paymentDeadlineHistoryRepository).save(any());

            // MVP-003: side effects are deferred via an AFTER_COMMIT event, never called directly.
            ArgumentCaptor<AuctionFinalizedEvent> captor = ArgumentCaptor.forClass(AuctionFinalizedEvent.class);
            verify(eventPublisher, times(1)).publishEvent(captor.capture());
            AuctionFinalizedEvent event = captor.getValue();
            assertThat(event.auction()).isSameAs(activeAuction);
            assertThat(event.winnerId()).isEqualTo(winnerId);
            assertThat(event.winningBid()).isEqualByComparingTo(new BigDecimal("1200000"));
            assertThat(event.participantIds()).containsExactly(winnerId);
        }

        @Test
        @DisplayName("Policy-bound аукцион: currentPrice >= raw reserve, но < buyer-facing reserve -> EXPIRED (резерв не достигнут)")
        void finalizeAuction_PolicyBound_CurrentPriceBelowBuyerFacingReserve_ShouldExpire() {
            // given: raw reserve 1,000,000, commissionRate 2% -> buyer-facing reserve 1,020,000.00.
            // currentPrice=1,010,000 clears the RAW reserve but not the buyer-facing one - this is
            // exactly the scenario Slice 1b-2 must get right: finalization must compare against
            // the buyer-facing value, not the seller-side stored one.
            Auction policyBound = new Auction(2L, new BigDecimal("900000"), new BigDecimal("1000000"),
                    null, new BigDecimal("10000"), fixedTime.minusHours(2), fixedTime.plusHours(2),
                    sellerId, clock);
            policyBound.setId(2L);
            policyBound.setStatus(AuctionStatus.ACTIVE);
            policyBound.setPolicyVersionId(5L);
            policyBound.setTotalBids(1);
            policyBound.setCurrentPrice(new BigDecimal("1010000"));
            policyBound.setWinnerId(winnerId);
            policyBound.setWinningBid(new BigDecimal("1010000"));

            when(policyVersionService.getById(5L)).thenReturn(
                    new PolicyVersion(5L, 1L, new BigDecimal("0.02"), true,
                            sellerId, fixedTime, sellerId, fixedTime, null, 0L, 0L));
            when(participantRepository.getParticipants(2L)).thenReturn(List.of(winnerId));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            boolean result = auctionFinalizer.finalizeAuction(policyBound);

            // then
            assertThat(result).isTrue();
            assertThat(policyBound.getStatus()).isEqualTo(AuctionStatus.EXPIRED);
            assertThat(policyBound.getPaymentDeadline()).isNull();
            verifyNoInteractions(standardPaymentDeadlineCalculator, paymentDeadlineHistoryRepository);

            // MVP-003: reserve not met -> no winner in the published event, even though the
            // Auction object was pre-seeded with a winnerId/winningBid from bidding (both must be
            // cleared by finalization to null, matching the local winnerId/winningBid variables
            // finalizeAuction actually uses - not the auction's pre-existing bid state).
            ArgumentCaptor<AuctionFinalizedEvent> captor = ArgumentCaptor.forClass(AuctionFinalizedEvent.class);
            verify(eventPublisher, times(1)).publishEvent(captor.capture());
            AuctionFinalizedEvent event = captor.getValue();
            assertThat(event.auction()).isSameAs(policyBound);
            assertThat(event.winnerId()).isNull();
            assertThat(event.winningBid()).isNull();
            assertThat(event.participantIds()).containsExactly(winnerId);
        }

        @Test
        @DisplayName("Policy-bound аукцион: currentPrice достигает buyer-facing reserve -> WAITING_FOR_PAYMENT")
        void finalizeAuction_PolicyBound_CurrentPriceMeetsBuyerFacingReserve_ShouldWaitForPayment() {
            // given: raw reserve 1,000,000 @ 2% -> buyer-facing reserve 1,020,000.00 exactly
            Auction policyBound = new Auction(2L, new BigDecimal("900000"), new BigDecimal("1000000"),
                    null, new BigDecimal("10000"), fixedTime.minusHours(2), fixedTime.plusHours(2),
                    sellerId, clock);
            policyBound.setId(2L);
            policyBound.setStatus(AuctionStatus.ACTIVE);
            policyBound.setPolicyVersionId(5L);
            policyBound.setTotalBids(1);
            policyBound.setCurrentPrice(new BigDecimal("1020000.00"));
            policyBound.setWinnerId(winnerId);
            policyBound.setWinningBid(new BigDecimal("1020000.00"));

            when(policyVersionService.getById(5L)).thenReturn(
                    new PolicyVersion(5L, 1L, new BigDecimal("0.02"), true,
                            sellerId, fixedTime, sellerId, fixedTime, null, 0L, 0L));
            when(participantRepository.getParticipants(2L)).thenReturn(List.of(winnerId));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));
            OffsetDateTime deadline = fixedTime.plusDays(1);
            when(standardPaymentDeadlineCalculator.computeInitialDeadline()).thenReturn(deadline);

            // when
            boolean result = auctionFinalizer.finalizeAuction(policyBound);

            // then
            assertThat(result).isTrue();
            assertThat(policyBound.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
            assertThat(policyBound.getPaymentDeadline()).isEqualTo(deadline);
        }

        @Test
        @DisplayName("Policy A -> Policy B опубликована -> аукцион под A всё ещё резолвится по A, не по текущей опубликованной")
        void finalizeAuction_PolicyCutover_ShouldStillResolveItsOwnFixedPolicyVersion() {
            // given: auction bound to Policy A (id=5). Policy B (id=9) has since been published
            // with a different rate - the finalizer must resolve by the auction's OWN
            // policyVersionId (5), never by "whatever is currently published".
            Auction policyBound = new Auction(2L, new BigDecimal("900000"), new BigDecimal("1000000"),
                    null, new BigDecimal("10000"), fixedTime.minusHours(2), fixedTime.plusHours(2),
                    sellerId, clock);
            policyBound.setId(2L);
            policyBound.setStatus(AuctionStatus.ACTIVE);
            policyBound.setPolicyVersionId(5L);
            policyBound.setTotalBids(1);
            policyBound.setCurrentPrice(new BigDecimal("1020000.00"));
            policyBound.setWinnerId(winnerId);
            policyBound.setWinningBid(new BigDecimal("1020000.00"));

            when(policyVersionService.getById(5L)).thenReturn(
                    new PolicyVersion(5L, 1L, new BigDecimal("0.02"), true,
                            sellerId, fixedTime, sellerId, fixedTime, null, 0L, 0L));
            when(participantRepository.getParticipants(2L)).thenReturn(List.of(winnerId));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));
            when(standardPaymentDeadlineCalculator.computeInitialDeadline()).thenReturn(fixedTime.plusDays(1));

            // when
            auctionFinalizer.finalizeAuction(policyBound);

            // then: resolved specifically by id 5, never by "current published" and never by id 9
            verify(policyVersionService, times(1)).getById(5L);
            verify(policyVersionService, never()).getById(9L);
            verify(policyVersionService, never()).getCurrentPublished();
        }
    }
}