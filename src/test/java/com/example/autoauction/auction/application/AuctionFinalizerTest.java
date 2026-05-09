package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
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
            assertThat(activeAuction.getStatus()).isEqualTo(AuctionStatus.COMPLETED);
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
}