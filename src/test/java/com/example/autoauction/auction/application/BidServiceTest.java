// auction/application/BidServiceTest.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.AuctionWebSocketController;
import com.example.autoauction.auction.web.dto.BidResponse;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты BidService - сервиса ставок")
class BidServiceTest {

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private DepositService depositService;

    @Mock
    private AuctionParticipantRepository participantRepository;

    @Mock
    private AuctionFinalizer auctionFinalizer;

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private AuctionWebSocketController webSocketController;

    @InjectMocks
    private BidService bidService;

    private Auction activeAuction;
    private Auction createdAuction;
    private Auction completedAuction;
    private Auction expiredAuction;

    private final Long auctionId = 1L;
    private final Long sellerId = 10L;
    private final Long bidderId1 = 100L;
    private final Long bidderId2 = 101L;
    private final Long nonExistentAuctionId = 999L;

    private final BigDecimal startingPrice = new BigDecimal("1000000");
    private final BigDecimal minBidStep = new BigDecimal("10000");
    private final BigDecimal reservePrice = new BigDecimal("1200000");
    private final BigDecimal buyNowPrice = new BigDecimal("1500000");
    private final BigDecimal validBid = new BigDecimal("1010000");
    private final BigDecimal tooLowBid = new BigDecimal("1005000");

    private OffsetDateTime now;
    private OffsetDateTime futureEndTime;

    @BeforeEach
    void setUp() {
        now = OffsetDateTime.now();
        futureEndTime = now.plusDays(7);

        // ⚠️ НЕ ставим стаб для rateLimitService здесь — каждый тест сам решит, нужен ли он
        // Это и есть причина UnnecessaryStubbingException — стаб, который не используется во всех тестах

        // Активный аукцион
        activeAuction = new Auction(
                1L, startingPrice, reservePrice, buyNowPrice, minBidStep,
                now.minusHours(1), futureEndTime, sellerId
        );
        activeAuction.setId(auctionId);
        activeAuction.setStatus(AuctionStatus.ACTIVE);
        activeAuction.setCurrentPrice(startingPrice);
        activeAuction.setTotalBids(0);
        activeAuction.setVersion(0L);

        // Созданный (ещё не активный) аукцион
        createdAuction = new Auction(
                2L, startingPrice, reservePrice, buyNowPrice, minBidStep,
                now.plusHours(1), futureEndTime, sellerId
        );
        createdAuction.setId(2L);
        createdAuction.setStatus(AuctionStatus.CREATED);

        // Завершённый аукцион
        completedAuction = new Auction(
                3L, startingPrice, reservePrice, buyNowPrice, minBidStep,
                now.minusDays(1), now.minusHours(1), sellerId
        );
        completedAuction.setId(3L);
        completedAuction.setStatus(AuctionStatus.COMPLETED);
        completedAuction.setCurrentPrice(new BigDecimal("1200000"));
        completedAuction.setWinnerId(bidderId1);
        completedAuction.setTotalBids(5);

        // Истекший аукцион
        expiredAuction = new Auction(
                4L, startingPrice, reservePrice, buyNowPrice, minBidStep,
                now.minusDays(2), now.minusDays(1), sellerId
        );
        expiredAuction.setId(4L);
        expiredAuction.setStatus(AuctionStatus.EXPIRED);
        expiredAuction.setCurrentPrice(startingPrice);
        expiredAuction.setTotalBids(0);
    }

    // ==================== УСПЕШНЫЕ СЦЕНАРИИ ====================

    @Nested
    @DisplayName("✅ Успешные сценарии ставок")
    class SuccessScenarios {

        @Test
        @DisplayName("Первая ставка на аукцион - успех, депозит замораживается")
        void placeBid_FirstBid_Success() {
            // given
            when(rateLimitService.tryAcquire(bidderId1)).thenReturn(true);
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);
            when(participantRepository.isParticipant(auctionId, bidderId1)).thenReturn(false);
            doNothing().when(participantRepository).registerParticipant(eq(auctionId), eq(bidderId1), any(BigDecimal.class));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            BidResponse response = bidService.placeBid(auctionId, validBid, bidderId1);

            // then
            assertNotNull(response);
            assertEquals(auctionId, response.auctionId());
            assertEquals(validBid, response.amount());
            assertEquals(validBid, response.newCurrentPrice());
            assertEquals(1, response.totalBids());
            assertEquals(AuctionStatus.ACTIVE, response.status());

            verify(rateLimitService, times(1)).tryAcquire(bidderId1);
            verify(depositService, times(1)).canParticipateInAuction(bidderId1, startingPrice);
            verify(depositService, times(1)).freezeForAuction(bidderId1, auctionId, startingPrice);
            verify(participantRepository, times(1)).registerParticipant(eq(auctionId), eq(bidderId1), any(BigDecimal.class));
            verify(auctionRepository, times(1)).save(any(Auction.class));
        }

        @Test
        @DisplayName("Вторая ставка того же пользователя - депозит НЕ замораживается повторно")
        void placeBid_SecondBidBySameUser_NoAdditionalFreeze() {
            // given
            when(rateLimitService.tryAcquire(bidderId1)).thenReturn(true);
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);
            when(participantRepository.isParticipant(auctionId, bidderId1))
                    .thenReturn(false)   // первый вызов
                    .thenReturn(true);   // второй вызов (уже участник)
            doNothing().when(participantRepository).registerParticipant(eq(auctionId), eq(bidderId1), any(BigDecimal.class));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Первая ставка
            bidService.placeBid(auctionId, validBid, bidderId1);

            // Обновляем цену для второй ставки
            BigDecimal secondBid = new BigDecimal("1020000");
            activeAuction.setCurrentPrice(validBid);
            activeAuction.setTotalBids(1);

            // when - вторая ставка
            BidResponse response = bidService.placeBid(auctionId, secondBid, bidderId1);

            // then
            assertNotNull(response);
            assertEquals(2, response.totalBids());

            verify(participantRepository, times(1)).registerParticipant(eq(auctionId), eq(bidderId1), any(BigDecimal.class));
            verify(depositService, times(1)).freezeForAuction(bidderId1, auctionId, startingPrice);
        }

        @Test
        @DisplayName("Ставка в последние минуты - аукцион продлевается")
        void placeBid_LastMinuteBid_AuctionExtended() {
            // given
            when(rateLimitService.tryAcquire(bidderId1)).thenReturn(true);
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);
            when(participantRepository.isParticipant(auctionId, bidderId1)).thenReturn(false);
            doNothing().when(participantRepository).registerParticipant(eq(auctionId), eq(bidderId1), any(BigDecimal.class));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            BidResponse response = bidService.placeBid(auctionId, validBid, bidderId1);

            // then
            assertNotNull(response);
            verify(rateLimitService, times(1)).tryAcquire(bidderId1);
        }
    }

    // ==================== НЕГАТИВНЫЕ СЦЕНАРИИ ====================

    @Nested
    @DisplayName("❌ Негативные сценарии - ошибки и исключения")
    class NegativeScenarios {

        @Test
        @DisplayName("Ставка на несуществующий аукцион - IllegalArgumentException")
        void placeBid_AuctionNotFound_ThrowsException() {
            when(rateLimitService.tryAcquire(bidderId1)).thenReturn(true);
            when(auctionRepository.findByIdWithLock(nonExistentAuctionId)).thenReturn(Optional.empty());

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> bidService.placeBid(nonExistentAuctionId, validBid, bidderId1));
            assertTrue(exception.getMessage().contains("не найден"));
        }

        @Test
        @DisplayName("Ставка на неактивный аукцион (CREATED) - IllegalStateException")
        void placeBid_AuctionNotActive_ThrowsException() {
            when(rateLimitService.tryAcquire(bidderId1)).thenReturn(true);
            when(auctionRepository.findByIdWithLock(2L)).thenReturn(Optional.of(createdAuction));

            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> bidService.placeBid(2L, validBid, bidderId1));
            assertTrue(exception.getMessage().contains("неактивный аукцион") ||
                    exception.getMessage().contains("CREATED"));
        }

        @Test
        @DisplayName("Ставка на свой собственный аукцион - IllegalArgumentException")
        void placeBid_OnOwnAuction_ThrowsException() {
            when(rateLimitService.tryAcquire(sellerId)).thenReturn(true);
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> bidService.placeBid(auctionId, validBid, sellerId));
            assertTrue(exception.getMessage().contains("собственный"));
        }

        @Test
        @DisplayName("Ставка ниже минимального шага - IllegalArgumentException")
        void placeBid_BidTooLow_ThrowsException() {
            when(rateLimitService.tryAcquire(bidderId1)).thenReturn(true);
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);
            when(participantRepository.isParticipant(auctionId, bidderId1)).thenReturn(false);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> bidService.placeBid(auctionId, tooLowBid, bidderId1));
            assertTrue(exception.getMessage().contains("слишком мала"));

            verify(depositService, times(1)).unfreezeForAuction(bidderId1, auctionId);
        }

        @Test
        @DisplayName("Недостаточно средств на депозите - IllegalArgumentException")
        void placeBid_InsufficientDeposit_ThrowsException() {
            when(rateLimitService.tryAcquire(bidderId1)).thenReturn(true);
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(false);
            when(participantRepository.isParticipant(auctionId, bidderId1)).thenReturn(false);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> bidService.placeBid(auctionId, validBid, bidderId1));
            assertTrue(exception.getMessage().contains("Недостаточно средств"));

            verify(depositService, never()).freezeForAuction(anyLong(), anyLong(), any());
        }
    }

    // ==================== ТЕСТЫ ИНТЕГРАЦИИ С ДЕПОЗИТАМИ ====================

    @Nested
    @DisplayName("💰 Тесты интеграции с депозитами")
    class DepositIntegrationTests {

        @Test
        @DisplayName("При успешной ставке депозит замораживается")
        void placeBid_Success_FreezesDeposit() {
            when(rateLimitService.tryAcquire(bidderId1)).thenReturn(true);
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);
            when(participantRepository.isParticipant(auctionId, bidderId1)).thenReturn(false);
            doNothing().when(participantRepository).registerParticipant(eq(auctionId), eq(bidderId1), any(BigDecimal.class));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            bidService.placeBid(auctionId, validBid, bidderId1);

            verify(depositService, times(1)).freezeForAuction(bidderId1, auctionId, startingPrice);
        }

        @Test
        @DisplayName("При ошибочной ставке депозит размораживается")
        void placeBid_Failed_UnfreezesDeposit() {
            when(rateLimitService.tryAcquire(bidderId1)).thenReturn(true);
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);
            when(participantRepository.isParticipant(auctionId, bidderId1)).thenReturn(false);

            assertThrows(IllegalArgumentException.class,
                    () -> bidService.placeBid(auctionId, tooLowBid, bidderId1));

            verify(depositService, times(1)).unfreezeForAuction(bidderId1, auctionId);
        }
    }

    // ==================== ТЕСТЫ УПРАВЛЕНИЯ УЧАСТНИКАМИ ====================

    @Nested
    @DisplayName("👥 Тесты управления участниками аукционов")
    class ParticipantsManagementTests {

        @Test
        @DisplayName("После ставки пользователь добавляется в список участников")
        void placeBid_AddsUserToParticipantsList() {
            when(rateLimitService.tryAcquire(bidderId1)).thenReturn(true);
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);
            when(participantRepository.isParticipant(auctionId, bidderId1)).thenReturn(false);
            doNothing().when(participantRepository).registerParticipant(eq(auctionId), eq(bidderId1), any(BigDecimal.class));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            bidService.placeBid(auctionId, validBid, bidderId1);

            verify(participantRepository, times(1)).registerParticipant(eq(auctionId), eq(bidderId1), any(BigDecimal.class));
        }

        @Test
        @DisplayName("Несколько пользователей - все добавляются в список")
        void placeBid_MultipleUsers_AllAdded() {
            when(rateLimitService.tryAcquire(anyLong())).thenReturn(true);
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(anyLong(), eq(startingPrice))).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(anyLong(), eq(auctionId), eq(startingPrice));
            when(participantRepository.isParticipant(eq(auctionId), anyLong())).thenReturn(false);
            doNothing().when(participantRepository).registerParticipant(eq(auctionId), anyLong(), any(BigDecimal.class));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            bidService.placeBid(auctionId, validBid, bidderId1);
            bidService.placeBid(auctionId, new BigDecimal("1020000"), bidderId2);

            verify(participantRepository, times(2)).registerParticipant(eq(auctionId), anyLong(), any(BigDecimal.class));
        }

        @Test
        @DisplayName("Получение участников несуществующего аукциона - пустой список")
        void getAuctionParticipants_NonExistentAuction_ReturnsEmpty() {
            when(participantRepository.getParticipants(nonExistentAuctionId)).thenReturn(List.of());

            List<Long> participants = bidService.getAuctionParticipants(nonExistentAuctionId);

            assertTrue(participants.isEmpty());
        }
    }

    // ==================== BUY NOW ТЕСТЫ ====================

    @Nested
    @DisplayName("🛒 Buy Now — сценарии")
    class BuyNowTests {

        @Test
        @DisplayName("Buy Now на ACTIVE аукцион — IllegalStateException")
        void buyNow_OnActiveAuction_Throws() {
            when(rateLimitService.tryAcquire(bidderId1)).thenReturn(true);
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> bidService.buyNow(auctionId, bidderId1));
            assertTrue(ex.getMessage().contains("CREATED"),
                    "Сообщение должно указывать что операция доступна только для CREATED");
        }

        @Test
        @DisplayName("Buy Now на COMPLETED аукцион — IllegalStateException")
        void buyNow_OnCompletedAuction_Throws() {
            when(rateLimitService.tryAcquire(bidderId1)).thenReturn(true);
            when(auctionRepository.findByIdWithLock(3L)).thenReturn(Optional.of(completedAuction));

            assertThrows(IllegalStateException.class,
                    () -> bidService.buyNow(3L, bidderId1));
        }

        @Test
        @DisplayName("Buy Now на собственный аукцион — IllegalArgumentException")
        void buyNow_OnOwnAuction_Throws() {
            when(rateLimitService.tryAcquire(sellerId)).thenReturn(true);
            when(auctionRepository.findByIdWithLock(2L)).thenReturn(Optional.of(createdAuction));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> bidService.buyNow(2L, sellerId));
            assertTrue(ex.getMessage().contains("собственный"));
        }

        @Test
        @DisplayName("Buy Now без buyNowPrice — IllegalStateException")
        void buyNow_NoBuyNowPrice_Throws() {
            Auction noBuyNow = new Auction(
                    5L, startingPrice, null, null, minBidStep,
                    now.plusHours(1), futureEndTime, sellerId
            );
            noBuyNow.setId(5L);
            noBuyNow.setStatus(AuctionStatus.CREATED);

            when(rateLimitService.tryAcquire(bidderId1)).thenReturn(true);
            when(auctionRepository.findByIdWithLock(5L)).thenReturn(Optional.of(noBuyNow));

            assertThrows(IllegalStateException.class,
                    () -> bidService.buyNow(5L, bidderId1));
        }

        @Test
        @DisplayName("Buy Now при недостаточном депозите — IllegalArgumentException")
        void buyNow_InsufficientDeposit_Throws() {
            when(rateLimitService.tryAcquire(bidderId1)).thenReturn(true);
            when(auctionRepository.findByIdWithLock(2L)).thenReturn(Optional.of(createdAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(false);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> bidService.buyNow(2L, bidderId1));
            assertTrue(ex.getMessage().contains("Недостаточно средств"));
        }

        @Test
        @DisplayName("Buy Now при превышении rate limit — RateLimitException")
        void buyNow_RateLimitExceeded_Throws() {
            when(rateLimitService.tryAcquire(bidderId1)).thenReturn(false);

            assertThrows(RateLimitException.class,
                    () -> bidService.buyNow(auctionId, bidderId1));

            verify(auctionRepository, never()).findByIdWithLock(any());
        }

        @Test
        @DisplayName("Buy Now с null auctionId — IllegalArgumentException")
        void buyNow_NullAuctionId_Throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> bidService.buyNow(null, bidderId1));
        }

        @Test
        @DisplayName("Buy Now с null buyerId — IllegalArgumentException")
        void buyNow_NullBuyerId_Throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> bidService.buyNow(auctionId, null));
        }
    }

    // ==================== RATE LIMIT ТЕСТЫ ====================

    @Nested
    @DisplayName("⏱ Rate Limit — ограничение ставок")
    class RateLimitTests {

        @Test
        @DisplayName("Ставка при превышении лимита — RateLimitException без обращения к БД")
        void placeBid_RateLimitExceeded_Throws_NoDbAccess() {
            when(rateLimitService.tryAcquire(bidderId1)).thenReturn(false);

            assertThrows(RateLimitException.class,
                    () -> bidService.placeBid(auctionId, validBid, bidderId1));

            verifyNoInteractions(auctionRepository);
            verifyNoInteractions(depositService);
        }
    }

    // ==================== ТЕСТЫ ГРАНИЧНЫХ СЛУЧАЕВ ====================

    @Nested
    @DisplayName("🎯 Граничные случаи")
    class EdgeCasesTests {

        @Test
        @DisplayName("Ставка равная минимальной допустимой - успех")
        void placeBid_ExactMinBidStep_Success() {
            BigDecimal exactMinBid = startingPrice.add(minBidStep);

            when(rateLimitService.tryAcquire(bidderId1)).thenReturn(true);
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);
            when(participantRepository.isParticipant(auctionId, bidderId1)).thenReturn(false);
            doNothing().when(participantRepository).registerParticipant(eq(auctionId), eq(bidderId1), any(BigDecimal.class));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            BidResponse response = bidService.placeBid(auctionId, exactMinBid, bidderId1);

            assertNotNull(response);
            assertEquals(exactMinBid, response.newCurrentPrice());
        }

        @Test
        @DisplayName("Ставка с null суммой - NullPointerException")
        void placeBid_NullAmount_ThrowsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> bidService.placeBid(auctionId, null, bidderId1));
        }

        @Test
        @DisplayName("Ставка с null auctionId - NullPointerException")
        void placeBid_NullAuctionId_ThrowsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> bidService.placeBid(null, validBid, bidderId1));
        }

        @Test
        @DisplayName("Ставка с null bidderId - NullPointerException")
        void placeBid_NullBidderId_ThrowsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> bidService.placeBid(auctionId, validBid, null));
        }
    }
}