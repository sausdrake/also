package com.example.autoauction.auction.infrastructure.scheduler;

import com.example.autoauction.admin.application.SchedulerHealthSignal;
import com.example.autoauction.auction.application.AuctionFinalizer;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты шедулера аукционов")
class AuctionCompletionSchedulerOptimizedTest {

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private AuctionFinalizer auctionFinalizer;

    @Mock
    private SchedulerHealthSignal schedulerHealthSignal;

    @InjectMocks
    private AuctionCompletionScheduler scheduler;

    private Auction expiredAuction;
    private OffsetDateTime now;

    @BeforeEach
    void setUp() {
        now = OffsetDateTime.now();

        expiredAuction = new Auction(
                1L,
                new BigDecimal("1000000"),
                new BigDecimal("1200000"),
                new BigDecimal("1500000"),
                new BigDecimal("10000"),
                now.minusDays(2),
                now.minusHours(1),
                1L
        );
        expiredAuction.setId(1L);
        expiredAuction.setStatus(AuctionStatus.ACTIVE);
    }

    @Test
    @DisplayName("Шедулер находит и завершает истёкшие ACTIVE-аукционы")
    void completeExpiredAuctions_ShouldFinalizeExpired() {
        when(auctionRepository.findByStatusAndEndTimeBefore(eq(AuctionStatus.ACTIVE), any(OffsetDateTime.class)))
                .thenReturn(List.of(expiredAuction));
        when(auctionFinalizer.finalizeAuction(expiredAuction)).thenReturn(true);

        scheduler.completeExpiredAuctions();

        verify(auctionRepository).findByStatusAndEndTimeBefore(eq(AuctionStatus.ACTIVE), any(OffsetDateTime.class));
        verify(auctionFinalizer).finalizeAuction(expiredAuction);
        verify(auctionRepository, never()).findByStatus(AuctionStatus.ACTIVE);
    }

    @Test
    @DisplayName("Шедулер отменяет CREATED-аукционы без подтверждения по истечении дедлайна")
    void cancelUnconfirmedAuctions_ShouldCancelOverdue() {
        Auction unconfirmed = new Auction(
                2L,
                new BigDecimal("500000"),
                null, null,
                new BigDecimal("5000"),
                now.plusMinutes(30),
                now.plusHours(3),
                1L
        );
        unconfirmed.setId(2L);

        when(auctionRepository.findUnconfirmedAuctionsPastDeadline(any(OffsetDateTime.class)))
                .thenReturn(List.of(unconfirmed));
        when(auctionFinalizer.cancelAuction(unconfirmed)).thenReturn(true);

        scheduler.cancelUnconfirmedAuctions();

        // R-1.5: scheduler must delegate to AuctionFinalizer.cancelAuction() (the canonical
        // place that unfreezes participants) rather than calling auction.cancel()+save()
        // directly.
        verify(auctionRepository).findUnconfirmedAuctionsPastDeadline(any(OffsetDateTime.class));
        verify(auctionFinalizer).cancelAuction(unconfirmed);
        verify(auctionRepository, never()).save(any());
    }

    @Test
    @DisplayName("R-1.5: если AuctionFinalizer сообщает, что аукцион уже завершён (isFinished), " +
            "шедулер не бросает исключение и не сохраняет аукцион напрямую")
    void cancelUnconfirmedAuctions_AlreadyFinishedByFinalizer_NoDirectSave() {
        Auction unconfirmed = new Auction(
                3L,
                new BigDecimal("500000"),
                null, null,
                new BigDecimal("5000"),
                now.plusMinutes(30),
                now.plusHours(3),
                1L
        );
        unconfirmed.setId(3L);

        when(auctionRepository.findUnconfirmedAuctionsPastDeadline(any(OffsetDateTime.class)))
                .thenReturn(List.of(unconfirmed));
        when(auctionFinalizer.cancelAuction(unconfirmed)).thenReturn(false);

        scheduler.cancelUnconfirmedAuctions();

        verify(auctionFinalizer).cancelAuction(unconfirmed);
        verify(auctionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Шедулер запускает подтверждённые CREATED-аукционы в startTime")
    void startConfirmedAuctions_ShouldStartReady() {
        Auction confirmed = new Auction(
                3L,
                new BigDecimal("300000"),
                null, null,
                new BigDecimal("3000"),
                now.minusMinutes(5),
                now.plusHours(2),
                1L
        );
        confirmed.setId(3L);
        confirmed.setConfirmedStart(true);

        when(auctionRepository.findConfirmedAuctionsReadyToStart(any(OffsetDateTime.class)))
                .thenReturn(List.of(confirmed));

        scheduler.startConfirmedAuctions();

        // Vehicle Auction-State Wiring: the scheduler now delegates the actual start (and its
        // atomic Vehicle transition) to AuctionFinalizer.startAuction() - matching how
        // cancelUnconfirmedAuctions() above delegates to cancelAuction() - rather than calling
        // auction.start()+auctionRepository.save() directly.
        verify(auctionRepository).findConfirmedAuctionsReadyToStart(any(OffsetDateTime.class));
        verify(auctionFinalizer).startAuction(confirmed);
        verify(auctionRepository, never()).save(any());
    }
}
