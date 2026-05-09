// auction/infrastructure/scheduler/AuctionCompletionSchedulerOptimizedTest.java
package com.example.autoauction.auction.infrastructure.scheduler;

import com.example.autoauction.auction.application.AuctionFinalizer;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.infrastructure.persistence.SpringDataAuctionJpaRepository;
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
@DisplayName("Тесты оптимизированного шедулера аукционов")
class AuctionCompletionSchedulerOptimizedTest {

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private AuctionFinalizer auctionFinalizer;

    @Mock
    private SpringDataAuctionJpaRepository jpaRepository;

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
                now.minusHours(1), // истек
                1L
        );
        expiredAuction.setId(1L);
        expiredAuction.setStatus(AuctionStatus.ACTIVE);
    }

    @Test
    @DisplayName("Шедулер использует оптимизированный запрос для поиска истекших аукционов")
    void completeExpiredAuctions_ShouldUseOptimizedQuery() {
        // given
        when(auctionRepository.findByStatusAndEndTimeBefore(eq(AuctionStatus.ACTIVE), any(OffsetDateTime.class)))
                .thenReturn(List.of(expiredAuction));
        when(auctionFinalizer.finalizeAuction(expiredAuction)).thenReturn(true);

        // when
        scheduler.completeExpiredAuctions();

        // then
        verify(auctionRepository, times(1))
                .findByStatusAndEndTimeBefore(eq(AuctionStatus.ACTIVE), any(OffsetDateTime.class));
        verify(auctionFinalizer, times(1)).finalizeAuction(expiredAuction);
        // НЕ используем старый метод
        verify(auctionRepository, never()).findByStatus(AuctionStatus.ACTIVE);
    }

    @Test
    @DisplayName("Шедулер использует массовое обновление для отмены просроченных CREATED аукционов")
    void cancelExpiredCreatedAuctions_ShouldUseMassUpdate() {
        // given
        when(jpaRepository.cancelExpiredCreatedAuctions(any(OffsetDateTime.class))).thenReturn(5);

        // when
        scheduler.cancelExpiredCreatedAuctions();

        // then
        verify(jpaRepository, times(1)).cancelExpiredCreatedAuctions(any(OffsetDateTime.class));
        // НЕ загружаем все аукционы в память
        verify(auctionRepository, never()).findByStatus(AuctionStatus.CREATED);
    }
}