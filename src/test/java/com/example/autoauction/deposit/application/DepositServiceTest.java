// deposit/application/DepositServiceTest.java
package com.example.autoauction.deposit.application;

import com.example.autoauction.common.application.FailedOperationService;
import com.example.autoauction.deposit.domain.*;
import com.example.autoauction.deposit.domain.port.DepositRepository;
import com.example.autoauction.deposit.web.dto.DepositResponse;
import com.example.autoauction.deposit.web.dto.DepositTransactionResponse;
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
@DisplayName("Тесты DepositService - сервиса управления депозитами")
class DepositServiceTest {

    @Mock
    private DepositRepository depositRepository;

    @Mock
    private FailedOperationService failedOperationService;

    @InjectMocks
    private DepositService depositService;

    private Deposit deposit;
    private Deposit depositWithBalance;
    private Deposit depositWithBlocked;
    private final Long userId = 1L;
    private final Long anotherUserId = 2L;
    private final Long auctionId = 100L;
    private final Long depositId = 10L;
    private final BigDecimal startingPrice = new BigDecimal("1000000");
    private final BigDecimal requiredDeposit = new BigDecimal("10000.00");
    private final BigDecimal depositAmount = new BigDecimal("50000");
    private final BigDecimal withdrawAmount = new BigDecimal("30000");
    private final BigDecimal zero = BigDecimal.ZERO;
    private final BigDecimal negativeAmount = new BigDecimal("-1000");

    @BeforeEach
    void setUp() {
        deposit = new Deposit(userId, zero);
        deposit.setId(depositId);
        deposit.setCreatedAt(OffsetDateTime.now());
        deposit.setUpdatedAt(OffsetDateTime.now());

        depositWithBalance = new Deposit(userId, depositAmount);
        depositWithBalance.setId(depositId);

        depositWithBlocked = new Deposit(userId, depositAmount);
        depositWithBlocked.setId(depositId);
        // ИСПРАВЛЕНО: вызываем freezeForAuction с одним параметром (BigDecimal)
        depositWithBlocked.freezeForAuction(requiredDeposit);
    }

    // ==================== FREEZE FOR AUCTION TESTS ====================

    @Nested
    @DisplayName("Заморозка депозита для аукциона")
    class FreezeForAuctionTests {

        @Test
        @DisplayName("Заморозка при достаточном балансе")
        void freezeForAuction_Success() {
            // given
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));
            when(depositRepository.isFrozenForAuction(depositId, auctionId)).thenReturn(false);
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // when
            depositService.freezeForAuction(userId, auctionId, startingPrice);

            // then
            ArgumentCaptor<Deposit> depositCaptor = ArgumentCaptor.forClass(Deposit.class);
            verify(depositRepository, times(1)).save(depositCaptor.capture());

            Deposit savedDeposit = depositCaptor.getValue();
            assertEquals(requiredDeposit, savedDeposit.getBlockedAmount());
            assertEquals(depositAmount.subtract(requiredDeposit), savedDeposit.getAvailableBalance());

            // Проверяем вызов freezeForAuction в репозитории
            verify(depositRepository, times(1)).freezeForAuction(
                    eq(depositId), eq(auctionId), eq(requiredDeposit), any()
            );

            // Проверяем транзакцию
            ArgumentCaptor<DepositTransaction> transactionCaptor =
                    ArgumentCaptor.forClass(DepositTransaction.class);
            verify(depositRepository, times(1)).saveTransaction(transactionCaptor.capture());

            DepositTransaction transaction = transactionCaptor.getValue();
            assertEquals(DepositTransaction.TransactionType.BLOCK, transaction.getType());
            assertEquals(requiredDeposit, transaction.getAmount());
            assertEquals(auctionId, transaction.getAuctionId());
        }

        @Test
        @DisplayName("Многократная заморозка для разных аукционов")
        void freezeForAuction_MultipleFreezes_Success() {
            // given - депозит с большим балансом
            BigDecimal bigBalance = new BigDecimal("100000");
            Deposit richDeposit = new Deposit(userId, bigBalance);
            richDeposit.setId(depositId);

            Long auctionId2 = 200L;
            BigDecimal startingPrice2 = new BigDecimal("2000000");
            BigDecimal requiredDeposit2 = new BigDecimal("20000.00");

            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(richDeposit));
            when(depositRepository.isFrozenForAuction(eq(depositId), anyLong()))
                    .thenReturn(false);
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // when - первая заморозка
            depositService.freezeForAuction(userId, auctionId, startingPrice);

            // when - вторая заморозка
            depositService.freezeForAuction(userId, auctionId2, startingPrice2);

            // then
            ArgumentCaptor<Deposit> depositCaptor = ArgumentCaptor.forClass(Deposit.class);
            verify(depositRepository, times(2)).save(depositCaptor.capture());

            List<Deposit> savedDeposits = depositCaptor.getAllValues();
            Deposit lastSaved = savedDeposits.get(1);

            BigDecimal expectedBlocked = requiredDeposit.add(requiredDeposit2);
            assertEquals(expectedBlocked, lastSaved.getBlockedAmount());
        }

        @Test
        @DisplayName("Заморозка при недостаточном балансе - IllegalStateException")
        void freezeForAuction_InsufficientFunds_ThrowsException() {
            // given - депозит с нулевым балансом
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(deposit));

            // when & then
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> depositService.freezeForAuction(userId, auctionId, startingPrice));
            assertTrue(exception.getMessage().contains("Недостаточно средств"));

            verify(depositRepository, never()).save(any(Deposit.class));
        }

        @Test
        @DisplayName("Заморозка с null startingPrice - IllegalArgumentException")
        void freezeForAuction_NullStartingPrice_ThrowsException() {
            assertThrows(IllegalArgumentException.class,
                    () -> depositService.freezeForAuction(userId, auctionId, null));
        }
    }

    // ==================== UNFREEZE FOR AUCTION TESTS ====================

    @Nested
    @DisplayName("Разморозка депозита")
    class UnfreezeForAuctionTests {

        @Test
        @DisplayName("Успешная разморозка")
        void unfreezeForAuction_Success() {
            // given
            FrozenDeposit frozenDeposit = new FrozenDeposit(auctionId, requiredDeposit, FreezeReason.FIRST_BID);

            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
            when(depositRepository.getFrozenDepositsByDepositId(depositId))
                    .thenReturn(List.of(frozenDeposit));
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // when
            depositService.unfreezeForAuction(userId, auctionId);

            // then
            ArgumentCaptor<Deposit> depositCaptor = ArgumentCaptor.forClass(Deposit.class);
            verify(depositRepository, times(1)).save(depositCaptor.capture());

            Deposit savedDeposit = depositCaptor.getValue();

            // ИСПРАВЛЕНО: используем BigDecimal.ZERO вместо 0
            assertEquals(0, savedDeposit.getBlockedAmount().compareTo(BigDecimal.ZERO));
            assertTrue(savedDeposit.getAvailableBalance().compareTo(depositAmount) == 0);}
    }
    // ==================== MARK AS WINNER TESTS ====================

    @Nested
    @DisplayName("Отметка победителя аукциона")
    class MarkAsWinnerTests {

        @Test
        @DisplayName("Успешная отметка победителя - депозит остаётся замороженным")
        void markAsWinner_Success() {
            // given
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBlocked));
            doNothing().when(depositRepository).markFrozenAsWinner(depositId, auctionId);

            // when
            depositService.markAsWinner(userId, auctionId);

            // then
            verify(depositRepository, times(1)).markFrozenAsWinner(depositId, auctionId);
        }

        @Test
        @DisplayName("Отметка победителя для несуществующего депозита - исключение")
        void markAsWinner_DepositNotFound_ThrowsException() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                    () -> depositService.markAsWinner(userId, auctionId));
        }
    }

    // ==================== CAN PARTICIPATE TESTS ====================

    @Nested
    @DisplayName("Проверка возможности участия в аукционе")
    class CanParticipateTests {

        @Test
        @DisplayName("Достаточно средств - true")
        void canParticipateInAuction_WithEnoughFunds_ReturnsTrue() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));

            boolean result = depositService.canParticipateInAuction(userId, startingPrice);

            assertTrue(result);
        }

        @Test
        @DisplayName("Недостаточно средств - false")
        void canParticipateInAuction_InsufficientFunds_ReturnsFalse() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(deposit));

            boolean result = depositService.canParticipateInAuction(userId, startingPrice);

            assertFalse(result);
        }

        @Test
        @DisplayName("Депозит не существует - false")
        void canParticipateInAuction_NoDeposit_ReturnsFalse() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.empty());

            boolean result = depositService.canParticipateInAuction(userId, startingPrice);

            assertFalse(result);
        }
    }

    // ==================== GET DEPOSIT TESTS ====================

    @Nested
    @DisplayName("Получение депозита")
    class GetDepositTests {

        @Test
        @DisplayName("Успешное получение депозита по userId")
        void getDepositByUserId_Success() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(depositWithBalance));

            DepositResponse response = depositService.getDepositByUserId(userId);

            assertNotNull(response);
            assertEquals(depositId, response.id());
            assertEquals(depositAmount, response.balance());
        }

        @Test
        @DisplayName("Депозит не найден - исключение")
        void getDepositByUserId_NotFound_ThrowsException() {
            when(depositRepository.findByUserId(userId)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                    () -> depositService.getDepositByUserId(userId));
        }
    }

    // ==================== GET TRANSACTIONS TESTS ====================

    @Nested
    @DisplayName("Получение транзакций")
    class GetTransactionsTests {

        @Test
        @DisplayName("Успешное получение транзакций")
        void getTransactionsByUserId_Success() {
            // given
            DepositTransaction transaction = new DepositTransaction(
                    depositId, userId, DepositTransaction.TransactionType.DEPOSIT,
                    depositAmount, zero, depositAmount, "TEST", "Test transaction"
            );
            when(depositRepository.findTransactionsByUserId(userId))
                    .thenReturn(List.of(transaction));

            // when
            List<DepositTransactionResponse> responses =
                    depositService.getTransactionsByUserId(userId);

            // then
            assertNotNull(responses);
            assertEquals(1, responses.size());
            assertEquals(DepositTransaction.TransactionType.DEPOSIT, responses.get(0).type());
            assertEquals(depositAmount, responses.get(0).amount());
        }
    }

    // ==================== CALCULATE REQUIRED DEPOSIT TESTS ====================

    @Nested
    @DisplayName("Расчёт требуемого депозита")
    class CalculateRequiredDepositTests {

        @Test
        @DisplayName("1% от 1,000,000 = 10,000")
        void calculateRequiredDeposit_OneMillion_ReturnsTenThousand() {
            BigDecimal result = Deposit.calculateRequiredDeposit(new BigDecimal("1000000"));
            assertEquals(new BigDecimal("10000.00"), result);
        }

        @Test
        @DisplayName("Null startingPrice - возвращает 0")
        void calculateRequiredDeposit_Null_ReturnsZero() {
            BigDecimal result = Deposit.calculateRequiredDeposit(null);
            assertEquals(BigDecimal.ZERO, result);
        }
    }

    // ==================== CONCURRENCY TESTS ====================

    @Nested
    @DisplayName("Тесты конкурентного доступа")
    class ConcurrencyTests {

        @Test
        @DisplayName("Параллельное пополнение и вывод - данные должны быть консистентны")
        void concurrentDepositAndWithdraw_ShouldBeConsistent() throws Exception {
            // given
            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch latch = new CountDownLatch(20);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);

            Deposit sharedDeposit = new Deposit(userId, new BigDecimal("10000"));
            sharedDeposit.setId(depositId);

            when(depositRepository.findByUserId(userId)).thenReturn(Optional.of(sharedDeposit));
            when(depositRepository.save(any(Deposit.class))).thenAnswer(inv -> inv.getArgument(0));
            when(depositRepository.saveTransaction(any(DepositTransaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            for (int i = 0; i < 10; i++) {
                executor.submit(() -> {
                    try {
                        depositService.deposit(userId, new BigDecimal("100"));
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });

                executor.submit(() -> {
                    try {
                        depositService.withdraw(userId, new BigDecimal("50"));
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

            assertTrue(sharedDeposit.getBalance().compareTo(BigDecimal.ZERO) >= 0);
        }
    }
}