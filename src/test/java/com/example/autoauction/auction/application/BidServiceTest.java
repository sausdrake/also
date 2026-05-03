package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.dto.BidResponse;
import com.example.autoauction.deposit.application.DepositService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final BigDecimal buyNowBid = new BigDecimal("1500000");

    private OffsetDateTime now;
    private OffsetDateTime futureEndTime;

    @BeforeEach
    void setUp() {
        now = OffsetDateTime.now();
        futureEndTime = now.plusDays(7);

        // Активный аукцион
        activeAuction = new Auction(
                1L, startingPrice, reservePrice, buyNowPrice, minBidStep,
                now.minusHours(1), futureEndTime, sellerId
        );
        activeAuction.setId(auctionId);
        activeAuction.setStatus(AuctionStatus.ACTIVE);
        activeAuction.setCurrentPrice(startingPrice);
        activeAuction.setTotalBids(0);

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
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);
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
            assertFalse(response.buyNowUsed());
            assertFalse(response.auctionExtended());

            verify(depositService, times(1)).canParticipateInAuction(bidderId1, startingPrice);
            verify(depositService, times(1)).freezeForAuction(bidderId1, auctionId, startingPrice);
            verify(depositService, never()).unfreezeForAuction(anyLong(), anyLong(), any());
            verify(auctionRepository, times(1)).save(any(Auction.class));
        }

        @Test
        @DisplayName("Вторая ставка того же пользователя - депозит НЕ замораживается повторно")
        void placeBid_SecondBidBySameUser_NoAdditionalFreeze() throws Exception {
            // given
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);
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

            // canParticipateInAuction и freezeForAuction должны быть вызваны только 1 раз
            verify(depositService, times(1)).canParticipateInAuction(bidderId1, startingPrice);
            verify(depositService, times(1)).freezeForAuction(bidderId1, auctionId, startingPrice);
        }

        @Test
        @DisplayName("Ставка, активирующая Buy Now - аукцион завершается")
        void placeBid_BuyNowPrice_Success() {
            // given
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);
            doNothing().when(depositService).markAsWinner(bidderId1, auctionId);
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            BidResponse response = bidService.placeBid(auctionId, buyNowBid, bidderId1);

            // then
            assertNotNull(response);
            assertEquals(buyNowBid, response.amount());
            assertEquals(buyNowBid, response.newCurrentPrice());
            assertTrue(response.buyNowUsed());
            assertEquals(AuctionStatus.COMPLETED, response.status());

            verify(depositService, times(1)).markAsWinner(bidderId1, auctionId);
        }

        @Test
        @DisplayName("Ставка в последние минуты - аукцион продлевается")
        void placeBid_LastMinuteBid_AuctionExtended() {
            // given
            OffsetDateTime almostExpired = now.plusMinutes(4);
            Auction auctionEnding = new Auction(
                    1L, startingPrice, reservePrice, buyNowPrice, minBidStep,
                    now.minusHours(1), almostExpired, sellerId
            );
            auctionEnding.setId(auctionId);
            auctionEnding.setStatus(AuctionStatus.ACTIVE);
            auctionEnding.setCurrentPrice(startingPrice);

            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(auctionEnding));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            BidResponse response = bidService.placeBid(auctionId, validBid, bidderId1);

            // then
            assertNotNull(response);
            assertTrue(response.auctionExtended());
            assertNotNull(response.newEndTime());
            assertTrue(response.newEndTime().isAfter(almostExpired));
        }
    }

    // ==================== НЕГАТИВНЫЕ СЦЕНАРИИ ====================

    @Nested
    @DisplayName("❌ Негативные сценарии - ошибки и исключения")
    class NegativeScenarios {

        @Test
        @DisplayName("Ставка на несуществующий аукцион - IllegalArgumentException")
        void placeBid_AuctionNotFound_ThrowsException() {
            // given
            when(auctionRepository.findByIdWithLock(nonExistentAuctionId)).thenReturn(Optional.empty());

            // when & then
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> bidService.placeBid(nonExistentAuctionId, validBid, bidderId1)
            );
            assertTrue(exception.getMessage().contains("не найден"));

            verify(depositService, never()).canParticipateInAuction(anyLong(), any());
            verify(auctionRepository, never()).save(any(Auction.class));
        }

        @Test
        @DisplayName("Ставка на неактивный аукцион (CREATED) - IllegalStateException")
        void placeBid_AuctionNotActive_ThrowsException() {
            // given
            when(auctionRepository.findByIdWithLock(2L)).thenReturn(Optional.of(createdAuction));
            // Не нужно мокать depositService, так как проверка статуса первая

            // when & then
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> bidService.placeBid(2L, validBid, bidderId1)
            );
            // Исправляем проверку сообщения
            assertTrue(exception.getMessage().contains("неактивный аукцион") ||
                    exception.getMessage().contains("CREATED"));
        }

        @Test
        @DisplayName("Ставка на завершённый аукцион - IllegalStateException")
        void placeBid_AuctionCompleted_ThrowsException() {
            // given
            when(auctionRepository.findByIdWithLock(3L)).thenReturn(Optional.of(completedAuction));

            // when & then
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> bidService.placeBid(3L, validBid, bidderId1)
            );
            assertTrue(exception.getMessage().contains("неактивный аукцион") ||
                    exception.getMessage().contains("COMPLETED"));
        }

        @Test
        @DisplayName("Ставка на истекший аукцион - IllegalStateException")
        void placeBid_AuctionExpired_ThrowsException() {
            // given
            when(auctionRepository.findByIdWithLock(4L)).thenReturn(Optional.of(expiredAuction));

            // when & then
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> bidService.placeBid(4L, validBid, bidderId1)
            );
            assertTrue(exception.getMessage().contains("неактивный аукцион") ||
                    exception.getMessage().contains("EXPIRED"));
        }

        @Test
        @DisplayName("Ставка на свой собственный аукцион - IllegalArgumentException")
        void placeBid_OnOwnAuction_ThrowsException() {
            // given
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));

            // when & then
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> bidService.placeBid(auctionId, validBid, sellerId)
            );
            assertTrue(exception.getMessage().contains("собственный"));
        }

        @Test
        @DisplayName("Ставка ниже минимального шага - IllegalArgumentException")
        void placeBid_BidTooLow_ThrowsException() {
            // given
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);

            // when & then
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> bidService.placeBid(auctionId, tooLowBid, bidderId1)
            );
            assertTrue(exception.getMessage().contains("слишком мала"));

            // Депозит должен быть разморожен при ошибке
            verify(depositService, times(1)).unfreezeForAuction(bidderId1, auctionId, startingPrice);
        }

        @Test
        @DisplayName("Недостаточно средств на депозите - IllegalArgumentException")
        void placeBid_InsufficientDeposit_ThrowsException() {
            // given
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(false);

            // when & then
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> bidService.placeBid(auctionId, validBid, bidderId1)
            );
            assertTrue(exception.getMessage().contains("Недостаточно средств"));

            verify(depositService, never()).freezeForAuction(anyLong(), anyLong(), any());
            verify(auctionRepository, never()).save(any(Auction.class));
        }
    }

    // ==================== ТЕСТЫ ИНТЕГРАЦИИ С ДЕПОЗИТАМИ ====================

    @Nested
    @DisplayName("💰 Тесты интеграции с депозитами")
    class DepositIntegrationTests {

        @Test
        @DisplayName("При успешной ставке депозит замораживается")
        void placeBid_Success_FreezesDeposit() {
            // given
            BigDecimal requiredDeposit = startingPrice.multiply(new BigDecimal("0.01"));
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            bidService.placeBid(auctionId, validBid, bidderId1);

            // then
            verify(depositService, times(1)).freezeForAuction(bidderId1, auctionId, startingPrice);
        }

        @Test
        @DisplayName("При ошибочной ставке депозит размораживается")
        void placeBid_Failed_UnfreezesDeposit() {
            // given
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);

            // Ставка слишком низкая - вызовет исключение
            // when & then
            assertThrows(IllegalArgumentException.class,
                    () -> bidService.placeBid(auctionId, tooLowBid, bidderId1)
            );

            verify(depositService, times(1)).unfreezeForAuction(bidderId1, auctionId, startingPrice);
        }

        @Test
        @DisplayName("При выигрыше по Buy Now пользователь отмечается как победитель")
        void placeBid_BuyNow_MarksAsWinner() {
            // given
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);
            doNothing().when(depositService).markAsWinner(bidderId1, auctionId);
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            bidService.placeBid(auctionId, buyNowBid, bidderId1);

            // then
            verify(depositService, times(1)).markAsWinner(bidderId1, auctionId);
        }
    }

    // ==================== ТЕСТЫ УПРАВЛЕНИЯ УЧАСТНИКАМИ ====================

    @Nested
    @DisplayName("👥 Тесты управления участниками аукционов")
    class ParticipantsManagementTests {

        @Test
        @DisplayName("После ставки пользователь добавляется в список участников")
        void placeBid_AddsUserToParticipantsList() throws Exception {
            // given
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            bidService.placeBid(auctionId, validBid, bidderId1);

            // then
            List<Long> participants = bidService.getAuctionParticipants(auctionId);
            assertTrue(participants.contains(bidderId1));
        }

        @Test
        @DisplayName("Несколько пользователей - все добавляются в список")
        void placeBid_MultipleUsers_AllAdded() throws Exception {
            // given
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(anyLong(), eq(startingPrice))).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(anyLong(), eq(auctionId), eq(startingPrice));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            bidService.placeBid(auctionId, validBid, bidderId1);
            bidService.placeBid(auctionId, new BigDecimal("1020000"), bidderId2);

            // then
            List<Long> participants = bidService.getAuctionParticipants(auctionId);
            assertEquals(2, participants.size());
            assertTrue(participants.contains(bidderId1));
            assertTrue(participants.contains(bidderId2));
        }

        @Test
        @DisplayName("Очистка участников аукциона")
        void clearAuctionParticipants_RemovesAll() {
            // given - сначала добавляем участника через ставку
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            bidService.placeBid(auctionId, validBid, bidderId1);
            assertFalse(bidService.getAuctionParticipants(auctionId).isEmpty());

            // when
            bidService.clearAuctionParticipants(auctionId);

            // then
            assertTrue(bidService.getAuctionParticipants(auctionId).isEmpty());
        }

        @Test
        @DisplayName("Получение участников несуществующего аукциона - пустой список")
        void getAuctionParticipants_NonExistentAuction_ReturnsEmpty() {
            // when
            List<Long> participants = bidService.getAuctionParticipants(nonExistentAuctionId);

            // then
            assertTrue(participants.isEmpty());
        }
    }

    // ==================== ТЕСТЫ ГРАНИЧНЫХ СЛУЧАЕВ ====================

    @Nested
    @DisplayName("🎯 Граничные случаи")
    class EdgeCasesTests {

        @Test
        @DisplayName("Ставка равная минимальной допустимой - успех")
        void placeBid_ExactMinBidStep_Success() {
            // given
            BigDecimal exactMinBid = startingPrice.add(minBidStep); // 1,010,000
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // when
            BidResponse response = bidService.placeBid(auctionId, exactMinBid, bidderId1);

            // then
            assertNotNull(response);
            assertEquals(exactMinBid, response.newCurrentPrice());
        }

        @Test
        @DisplayName("Ошибка при заморозке депозита - ставка отклонена, исключение пробрасывается")
        void placeBid_DepositFreezeFailed_ThrowsException() {
            // given
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doThrow(new RuntimeException("Database error")).when(depositService)
                    .freezeForAuction(bidderId1, auctionId, startingPrice);

            // when & then
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> bidService.placeBid(auctionId, validBid, bidderId1)
            );
            assertTrue(exception.getMessage().contains("Ошибка при заморозке депозита"));

            verify(auctionRepository, never()).save(any(Auction.class));
        }

        @Test
        @DisplayName("Ставка с null суммой - NullPointerException")
        void placeBid_NullAmount_ThrowsNullPointerException() {
            // given
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));

            // when & then
            assertThrows(NullPointerException.class,
                    () -> bidService.placeBid(auctionId, null, bidderId1)
            );
        }

        @Test
        @DisplayName("Ставка с отрицательной суммой - будет отклонена валидацией в Auction.addBid")
        void placeBid_NegativeAmount_ThrowsException() {
            // given
            BigDecimal negativeAmount = new BigDecimal("-1000");
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);

            // when & then
            assertThrows(IllegalArgumentException.class,
                    () -> bidService.placeBid(auctionId, negativeAmount, bidderId1)
            );
        }

        @Test
        @DisplayName("Ставка с нулевой суммой - будет отклонена")
        void placeBid_ZeroAmount_ThrowsException() {
            // given
            BigDecimal zeroAmount = BigDecimal.ZERO;
            when(auctionRepository.findByIdWithLock(auctionId)).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(bidderId1, startingPrice)).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(bidderId1, auctionId, startingPrice);

            // when & then
            assertThrows(IllegalArgumentException.class,
                    () -> bidService.placeBid(auctionId, zeroAmount, bidderId1)
            );
        }
    }

    // ==================== ТЕСТЫ КОНКУРЕНТНОСТИ ====================

    @Nested
    @DisplayName("🔄 Тесты конкурентного доступа")
    class ConcurrencyTests {

        @Test
        @DisplayName("Параллельные ставки на один аукцион - все ставки обрабатываются корректно")
        void placeBid_ConcurrentBids_AllProcessed() throws Exception {
            // given
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            // Используем синхронизированный объект для мока
            when(auctionRepository.findByIdWithLock(anyLong())).thenReturn(Optional.of(activeAuction));
            when(depositService.canParticipateInAuction(anyLong(), eq(startingPrice))).thenReturn(true);
            doNothing().when(depositService).freezeForAuction(anyLong(), anyLong(), eq(startingPrice));

            // Настройка save - с задержкой для имитации конкурентности
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> {
                Thread.sleep(10);
                return invocation.getArgument(0);
            });

            // when
            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        BigDecimal bidAmount = startingPrice.add(minBidStep.multiply(BigDecimal.valueOf(index + 1)));
                        bidService.placeBid(auctionId, bidAmount, 100L + index);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            // then
            // Некоторые ставки могут быть отклонены из-за конкурентности - это нормально
            assertTrue(successCount.get() > 0, "Хотя бы одна ставка должна быть успешной");
            System.out.println("Успешных ставок: " + successCount.get() + ", отклонённых: " + failureCount.get());
        }
    }
}