package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.policy.domain.PolicyVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

            verify(depositService, times(2)).unfreezeForAuction(anyLong(), eq(auctionId));
            verify(participantRepository, times(1)).clearParticipants(auctionId);
            verify(auctionRepository, times(1)).save(activeAuction);
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

            // when
            auctionFinalizer.finalizeWithBuyNow(activeAuction, winnerId, buyNowAmount);

            // then
            assertThat(activeAuction.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
            assertThat(activeAuction.getWinnerId()).isEqualTo(winnerId);
            assertThat(activeAuction.getCurrentPrice()).isEqualTo(buyNowAmount);
            assertThat(activeAuction.getWinningBid()).isEqualTo(buyNowAmount);

            verify(depositService, times(1)).markAsWinner(winnerId, auctionId);
            verify(depositService, times(1)).unfreezeForAuction(eq(loserId), eq(auctionId));
            verify(participantRepository, times(1)).clearParticipants(auctionId);
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

            // when
            boolean result = auctionFinalizer.finalizeAuction(activeAuction);

            // then
            assertThat(result).isTrue();
            assertThat(activeAuction.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
            verifyNoInteractions(policyVersionService);
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
                            fixedTime, sellerId, fixedTime));
            when(participantRepository.getParticipants(2L)).thenReturn(List.of(winnerId));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            boolean result = auctionFinalizer.finalizeAuction(policyBound);

            // then
            assertThat(result).isTrue();
            assertThat(policyBound.getStatus()).isEqualTo(AuctionStatus.EXPIRED);
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
                            fixedTime, sellerId, fixedTime));
            when(participantRepository.getParticipants(2L)).thenReturn(List.of(winnerId));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            boolean result = auctionFinalizer.finalizeAuction(policyBound);

            // then
            assertThat(result).isTrue();
            assertThat(policyBound.getStatus()).isEqualTo(AuctionStatus.WAITING_FOR_PAYMENT);
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
                            fixedTime, sellerId, fixedTime));
            when(participantRepository.getParticipants(2L)).thenReturn(List.of(winnerId));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

            // when
            auctionFinalizer.finalizeAuction(policyBound);

            // then: resolved specifically by id 5, never by "current published" and never by id 9
            verify(policyVersionService, times(1)).getById(5L);
            verify(policyVersionService, never()).getById(9L);
            verify(policyVersionService, never()).getCurrentPublished();
        }
    }
}